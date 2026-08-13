# ADR-002: Forward Mark Compute-on-Demand with EOD Snapshot

## Status

PROPOSED — 2026-08-13

## Context

### Problem Statement

The current S5b forward mark design stores pre-computed marks at **15-minute
interval granularity per position**. For a 10-year wind PPA, this produces
~350,000 rows per position (10 × 365 × 96 intervals). At scale (200 positions
per tenant × 200 tenants), this approaches **14 billion rows** — a storage and
write amplification problem with no corresponding business benefit.

The root cause is that S5b pre-multiplies two independently stored data sources:

- **Forward curve prices** (S4): shared market reference data, published at
  monthly/quarterly/yearly granularity by exchanges (EEX, Nord Pool, ICE Endex).
  A single monthly price applies to ~2,880 fifteen-minute intervals.
- **Resolved volumes** (S6b): per-position forecast volumes at 15-minute
  granularity. These genuinely vary at 15-min (wind/solar profiles).

The pre-multiplication `markValue = curvePrice × resolvedQty × 0.25h` stores
redundant information: the same shaped monthly price is repeated across thousands
of intervals, and the volume is already stored in S6b.

### Decision Drivers

1. **EU power market price granularity.** Exchanges publish forward prices at:
   - Monthly: liquid for 6–12 months (EEX, Nord Pool)
   - Quarterly: liquid for 2–3 years
   - Yearly (Calendar): liquid for 6–10 years
   - No exchange publishes hourly or 15-min forward prices beyond D+1.
   - The "15-min forward price" used in S5b is a synthetic construct produced by
     shaping a monthly price through hourly profile coefficients.

2. **Write amplification on curve tick.** When a forward curve updates, the
   current design requires `ForwardMarkJob` to re-strike millions of interval
   marks across all affected positions. The actual change is a single monthly
   price in S4.

3. **Storage cost.** Per-interval marks for 200 tenants × 300 positions × 10
   years × 96 intervals/day × 365 days × 2 (base/peak) ≈ **42 billion rows**.
   At ~100 bytes per row, this is ~4 TB of essentially redundant data.

4. **As-of MtM requirement.** Traders and risk analysts need historical MtM
   ("what was my portfolio MtM on date X?"). The current S5b is ephemeral
   (D-3: overwrite on each mark cycle), so it cannot serve this. Making S5b
   bitemporal at 15-min granularity would multiply storage further.

5. **EMIR Art. 9 daily valuation.** Regulators require daily MtM valuations.
   This is a per-position per-delivery-month per-business-day snapshot — NOT
   a per-interval-per-position record. The regulatory grain is much coarser
   than the current S5b grain.

### What the Current Design Does

```
CurveTick event
  → ForwardMarkJob
    → For each affected position (via dependency index, FR-103):
        → For each 15-min interval in the forward delivery window:
            → Evaluate price expression against curve
            → markValue = evaluatedPrice × resolvedQty(S6b) × 0.25h
            → INSERT/UPSERT into forward_mark table (S5b)
    → Kafka: ForwardMarksRefreshed
```

Each curve tick writes O(positions × intervals) rows. For a portfolio with 50
positions delivering over 2 years, a single curve tick writes
50 × 2 × 365 × 96 ≈ **3.5 million rows**.

## Decision

**Replace S5b per-interval persistent storage with:**

1. **`ForwardMarkService` (compute-on-demand):** A domain port that evaluates
   `price(expression, curve) × volume(S6b)` at query time. Caches evaluated
   monthly prices in Redis with short TTL keyed by
   `(expressionId, curveVersions, month)`.

2. **S5c EOD Mark Snapshot:** A daily batch-produced snapshot at the grain of
   `(position × delivery-month × business-date)`. This serves as-of queries,
   EMIR daily valuation, and PnL attribution.

3. **S7 `forwardMarkValue` population:** The rollup pipeline calls
   `ForwardMarkService` to compute monthly MtM
   (`curvePrice(month) × totalMwh(month)`) and stores the result on the rollup
   cell. No intermediate S5b storage required.

### Architecture After

```
                        ┌─────────────────────────────┐
                        │  S4 Forward Curves           │
                        │  (monthly/quarterly/yearly,  │
                        │   versioned per tick)         │
                        └──────────┬──────────────────┘
                                   │
                     ┌─────────────┼──────────────────┐
                     │             │                   │
                     ▼             ▼                   ▼
┌──────────────┐  ┌────────────────────┐  ┌──────────────────┐
│ S6b Trade    │  │ ForwardMarkService │  │ S7 Rollup Cell   │
│ Interval     │──│ (compute on        │──│ forwardMarkValue │
│ Cache        │  │  demand; caches    │  │ (pre-aggregated  │
│ (15-min vol) │  │  monthly prices)   │  │  monthly MtM)    │
└──────────────┘  └────────┬───────────┘  └──────────────────┘
                           │
                           ▼
               ┌──────────────────────────┐
               │ S5c EOD Snapshot          │
               │ (daily batch,             │
               │  position × month × day)  │
               │ ← as-of / EMIR store      │
               └──────────────────────────┘
```

### S5c EOD Mark Snapshot Schema

```sql
CREATE TABLE market_data.forward_mark_snapshot (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    tenant_id           VARCHAR(64) NOT NULL,
    portfolio_id        VARCHAR(64) NOT NULL,
    position_id         UUID NOT NULL,
    snapshot_date       DATE NOT NULL,           -- business date (CET)
    snapshot_time       TIMESTAMPTZ NOT NULL,    -- exact UTC computation instant
    delivery_month      DATE NOT NULL,           -- first day of the forward month
    forward_mtm         NUMERIC(15, 4) NOT NULL, -- computed MtM for position-month
    curve_id            VARCHAR(64),             -- primary curve used
    curve_version       BIGINT,                  -- curve version at snapshot time
    volume_version      BIGINT,                  -- S3 volume series version
    expression_version  BIGINT,                  -- price expression version
    total_mwh           NUMERIC(15, 4),          -- total energy used in computation
    avg_price           NUMERIC(15, 8),          -- effective weighted avg price
    currency            VARCHAR(3) NOT NULL,

    PRIMARY KEY (tenant_id, position_id, snapshot_date, delivery_month)
);

CREATE INDEX idx_fms_portfolio_date
    ON market_data.forward_mark_snapshot (tenant_id, portfolio_id, snapshot_date);
```

**Row count estimate:** 200 positions × 120 forward months × 365 days/year
= ~8.8M rows/year per tenant. At 200 tenants = ~1.76B rows/year. At ~150 bytes
per row ≈ 264 GB/year — manageable with time-based partitioning and retention
policy (e.g., retain 2 years of daily snapshots, archive to monthly thereafter).

### ForwardMarkService Port

```java
// pv-domain/port/service/ForwardMarkService.java
public interface ForwardMarkService {

    /** Compute MtM for a position-month. Used by L3 position contribution. */
    MonthlyMark computeMonthlyMark(
        String tenantId, UUID positionId,
        Instant monthStart, Instant monthEnd);

    /** Compute MtM per interval for a position-day. Used by L4 forward view. */
    List<IntervalMark> computeIntervalMarks(
        String tenantId, UUID positionId,
        Instant dayStart, Instant dayEnd);

    /** Compute portfolio-level MtM for a period. Used by rollup pipeline. */
    BigDecimal computePortfolioMtm(
        String tenantId, String portfolioId,
        Instant periodStart, Instant periodEnd);
}

public record MonthlyMark(
    UUID positionId,
    Instant monthStart,
    Instant monthEnd,
    BigDecimal forwardMtm,
    BigDecimal totalMwh,
    BigDecimal avgPrice,
    String curveId,
    long curveVersion,
    long volumeVersion,
    String currency
) {}

public record IntervalMark(
    Instant intervalStart,
    Instant intervalEnd,
    UUID positionId,
    String tradeLegId,
    BigDecimal resolvedQty,       // from S6b
    BigDecimal resolvedEnergy,    // from S6b
    BigDecimal evaluatedPrice,    // from S4 + expression evaluation + shaping
    BigDecimal markValue,         // evaluatedPrice × resolvedEnergy
    String curveId,
    long curveVersion,
    String currency
) {}
```

### Computation Flow

```
computeIntervalMarks(tenantId, positionId, dayStart, dayEnd):
  1. Load position from S1 → get price expression ref, trade-leg ID
  2. Load S6b intervals for trade-leg + day range → resolved volumes
  3. Resolve monthly curve price:
     a. Check Redis cache: key = (expressionId, curveVersionHash, month)
     b. On miss: evaluate price expression against S4 curve at current version
     c. Cache result with TTL = 60s (invalidated on curve tick)
  4. Apply hourly shaping coefficients (if expression defines shaping)
  5. For each S6b interval:
     markValue = shapedPrice(interval) × resolvedEnergy(interval)
  6. Return List<IntervalMark>
```

### Curve Tick Handling (Replaces ForwardMarkJob)

```
CurveTick event
  → ForwardCurveUpdatedConsumer
    → Invalidate Redis cache entries for affected curve ID
    → Identify affected rollup cells via dependency index (FR-103)
    → For each affected (deliveryPoint, portfolio, month):
        → Recompute forwardMarkValue = computePortfolioMtm(...)
        → Upsert into rollup cell
    → Kafka: RollupRefreshed
```

Write volume per curve tick: O(affected rollup cells), not O(positions × intervals).
For a curve affecting 50 positions across 24 months ≈ 50 × 24 × 4 granularities
= **4,800 rollup cell upserts** vs. the previous 3.5 million interval inserts.

### EOD Snapshot Job

```
Scheduled daily at 18:00 CET (after market close):
  For each tenant:
    For each portfolio:
      For each active position with forward delivery:
        For each forward delivery month:
          mtm = ForwardMarkService.computeMonthlyMark(...)
          INSERT INTO forward_mark_snapshot (...)
```

## Alternatives Considered

### A. Keep S5b at 15-min, add bitemporality

- Storage: ~42B rows across all tenants, growing with bitemporality
- Write amplification: unchanged (millions of rows per curve tick)
- As-of: supported but at extreme storage cost
- **Rejected:** storage cost is not justified when the price input is monthly

### B. Keep S5b at 15-min, no bitemporality (status quo)

- No as-of capability for forward MtM
- EMIR daily valuation not captured
- Cannot answer "what was my MtM on date X?"
- **Rejected:** does not meet risk/regulatory requirements for as-of MtM

### C. Store S5b at monthly granularity (per-position per-month)

- Greatly reduces storage (~120 rows per position per 10Y)
- Still pre-multiplies price × volume
- Still requires re-strike on curve tick (but much fewer rows)
- **Considered but not preferred:** the compute-on-demand model eliminates
  the S5b write path entirely and is simpler; monthly granularity storage is
  achieved via S5c snapshots which serve a clearer purpose (EOD audit/EMIR)

### D. Store only S5c snapshots, compute everything else on demand (chosen)

- Zero per-interval persistent storage for forward marks
- S5c provides as-of at the right grain (position × month × business-date)
- Forward MtM is trivially computable from S4 + S6b
- Curve tick handling becomes cache invalidation + rollup cell update
- **Chosen:** cleanest separation of concerns, lowest storage, fastest updates

## Consequences

### Positive

- **~99.7% storage reduction** for forward mark data (42B rows → ~8.8M/year
  per tenant for S5c snapshots)
- **~99.9% write reduction** on curve tick (3.5M interval inserts → ~4,800
  rollup cell upserts)
- **As-of MtM capability** via S5c snapshots — serves both risk dashboard
  and EMIR daily valuation
- **PnL attribution** possible by comparing consecutive S5c snapshots
  (what drove MtM change: price or volume?)
- **Simpler architecture:** forward marks are a computation, not stored state.
  Eliminates `ForwardMarkStore` port, `ForwardMarkJob`, and the
  `forward_mark` table
- **Price-volume separation:** forward prices are shared reference data (S4),
  volumes are per-position (S6b). Each stored at its natural granularity

### Negative

- **L4 forward day view latency:** computing 96 interval marks on demand
  requires price expression evaluation + S6b lookup. With Redis-cached monthly
  prices, this is ~1-5ms. Without cache (cold start), up to ~50ms for complex
  expressions. Acceptable for interactive use.
- **Rollup pipeline dependency:** the rollup pipeline now calls
  `ForwardMarkService` instead of reading pre-stored S5b rows. This introduces
  a runtime dependency on S4 curve availability during rollup materialization.
  If S4 is unavailable, `forwardMarkValue` cannot be computed.
- **Price expression evaluation at query time:** for complex expressions
  (multi-index baskets, FX conversion), evaluation is more expensive than a
  table lookup. Mitigated by Redis caching of evaluated monthly prices.
- **EOD snapshot job is a new batch process:** requires scheduling
  infrastructure, monitoring, and error handling. Failure to run means no
  S5c snapshot for that business day.

### Neutral

- **D-3 (forward marks ephemeral) is preserved:** S5b was ephemeral (overwrite).
  The compute-on-demand model has no persistent S5b at all. S5c snapshots are
  a new subsystem with its own persistence model (append-only daily snapshots).
  D-3 applies to the live/current mark, which is now computed, not stored.
- **D-12 (S6b rebuildable) is unaffected:** S6b continues to store 15-min
  volumes. The compute model reads S6b as before.
- **Dashboard API contract is unchanged:** the backend tech spec's REST
  endpoints return the same DTOs. The implementation changes from "read S5b"
  to "call ForwardMarkService", but the API surface is identical.

## Subsystems Affected

| Subsystem | Change |
|-----------|--------|
| **S4 Forward Curves** | No change to storage. Curve versioning is already in place. |
| **S5b Forward Marks** | **Eliminated as persistent storage.** Replaced by `ForwardMarkService` compute port. The `forward_mark` table and `ForwardMarkStore` port are removed. |
| **S5c EOD Snapshot** | **New subsystem.** `forward_mark_snapshot` table, `EodMarkSnapshotJob` batch process, `EodMarkSnapshotRepository` port. |
| **S6b Trade Interval Cache** | No change. Continues to store 15-min resolved volumes. |
| **S7 Rollup Cells** | `forwardMarkValue` is now populated by `ForwardMarkService` during rollup materialization, not by reading S5b rows. |
| **ForwardMarkJob** | **Replaced.** Curve tick handling becomes cache invalidation + targeted rollup cell recomputation. |
| **Price expression evaluation** | Invoked at query time (via `ForwardMarkService`) with Redis-cached monthly results. Previously invoked at mark-strike time. |

## Constraints Compatibility

| Constraint | Status |
|------------|--------|
| D-2 (Price = expression reference) | Compatible. Price expressions are evaluated at query time through the existing expression evaluator. |
| D-3 (Forward marks ephemeral) | Compatible. Live forward marks are computed on demand — even more ephemeral than before (not stored at all). S5c snapshots are a separate append-only audit store. |
| D-12 (S6b optional, rebuildable) | Compatible. S6b is read by `ForwardMarkService` at query time. If S6b is empty, forward volume is unavailable and MtM = null. |
| D-13 (Library-first, Spring-free) | Compatible. `ForwardMarkService` port is in `pv-domain`. Implementation in `pv-domain/service/`. Redis cache adapter in `pv-redis`. |
| D-14 (Simulator patterns in pv-app only) | Compatible. EOD snapshot job scheduling is in `pv-app` (simulator) or the future production host. |

## Dependencies

- **S4 forward curve versioning** must provide a way to look up the current
  curve version for a given curve ID and delivery month.
- **Price expression evaluator** must support evaluation given a curve version
  (not just "latest"). For as-of computation via S5c, the evaluator is not
  needed — the snapshot already stores the computed value.
- **Redis** (or equivalent cache) for evaluated monthly price caching.
  Fallback: compute without cache (acceptable for low-concurrency simulator).

## References

- Functional spec: `docs/functional-spec/claude-gen/position-pnl-dashboard.functional.md` v3.0
- Backend tech spec: `docs/technical-spec/claude-gen/position-pnl-dashboard-v1.0.md`
- UI spec: `docs/ui-spec/claude-gen/position-pnl-dashboard-v1.0.md`
- OQ-12 (S6b bitemporality — closed, no)
- OQ-6 (forward mark granularity — resolved by this ADR)
- OI-6 (forwardMarkValue population — resolved by this ADR)
