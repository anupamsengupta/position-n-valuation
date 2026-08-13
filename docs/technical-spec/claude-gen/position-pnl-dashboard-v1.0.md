# Technical Specification -- Position & PnL Dashboard v1.0

## S1 -- Metadata & Status

| Field | Value |
|-------|-------|
| Author | solutions-architect |
| Status | DRAFT |
| Version | 1.0 |
| Date | 2026-08-13 |
| Depends On | `position-pnl-dashboard.functional.md` v3.0, ADR-001 (pattern catalog), ADR-002 (Forward Mark Compute-on-Demand), `functional-spec-position-valuation-v1.0.md` |
| Layer | **Library-scope** (new ports in `pv-domain`, new adapter methods in `pv-persistence`) + **Simulator-scope** (new REST controllers and DTOs in `pv-app`) |
| Subsystems Touched | S1 (Position Ledger, read-only), S4 (Forward Curves, read-only via ForwardMarkService), S5a (Settlement Cells, read-only), S5c (EOD Snapshot, read-only), S6b (Trade Interval Cache, read-only), S7 (Rollups, read + write for DAILY materialization) |
| ADR References | ADR-002 (Forward Mark Compute-on-Demand with EOD Snapshot) |

Six subsystems touched. All are read-only from the dashboard's perspective except S7, which gains DAILY granularity materialization. Forward marks are computed on demand by `ForwardMarkService` (ADR-002) from S4 curves × S6b volumes — there is no persistent S5b store. S5c EOD snapshots serve as-of/EMIR queries but are not displayed on this dashboard.

---

## S2 -- Scope & Non-Scope

### 2.1 In Scope

1. **New domain port:** `DashboardQueryService` in `pv-domain/port/service/` -- a single read-only query facade that serves all four dashboard levels (L1 portfolio cards, L2 period grid, L3 trade-level view, L4 interval detail). Pattern #18 (Port + Adapter).
2. **New repository methods** on existing ports: `RollupRepository.findByPortfolio()` (Q-1), `PositionLedgerRepository.findByPortfolioAndDeliveryRange()` (Q-2). Pattern #18.
3. **New domain value objects** (records) for dashboard query results: `PortfolioSummary`, `PositionContribution`, `DailyAggregate`, `ForwardIntervalDetail`. Pattern #3 (Value Object).
4. **DAILY granularity** added to S7 materialization pipeline in `RollupMaterializationService`. Extends existing Pattern #15 (Template Method).
5. **Sub-daily aggregation service** for 30-min and 60-min rollup of S5a settlement cells and ForwardMarkService-computed/S6b forward data. Domain logic (FR-035 TWA rules) stays server-side. Pattern #9 (Strategy).
6. **Simulator-scope REST endpoints** in `pv-app` for dashboard API. New `DashboardController` with endpoints mapping to L1--L4. DTOs in `pv-app/dto/dashboard/`.
7. **New adapter method** on `TradeIntervalCache` port: `getForPortfolioAndRange()` for portfolio-scoped S6b queries (Q-7).

### 2.2 Defers To

1. **S5a bitemporality** -- the functional spec notes this as a prerequisite. The dashboard queries current-knowledge settlement cells (no `knownTo` filter needed on the current delete-and-recreate model). When S5a gains bitemporality, the dashboard query will add `knownTo IS NULL` naturally. This spec does not design the S5a bitemporality change.
2. **S6b bitemporality** -- per OQ-12 resolution: no bitemporality on S6b. Dashboard reads current cache state.
3. **Peak/off-peak calendar (FR-026)** -- `isPeak` on rollup cells is currently always `false`. When `MarketCalendar` is implemented, peak/off-peak split will flow through existing rollup materialization. No dashboard-specific work needed beyond passing the `isPeak` filter.
4. **Staleness detection (AC-L1-08, Q-8)** -- requires comparing the S7 rollup cell's curve/volume versions against current S4 curve versions (per ADR-002). This is a cross-cutting concern and is deferred to a follow-up spec. The dashboard API will return the rollup cell's `versionHash` and computation timestamp so the UI or a future service can determine staleness.
5. **Real-time push (OQ-7)** -- deferred. The dashboard will rely on REST polling. SSE/WebSocket push is a separate enhancement once the production hosting layer exists.
6. **Cross-currency aggregation** -- per functional spec: out of scope. Separate subtotals per currency.
7. **Production hosting layer** -- all REST endpoints designed here are simulator-scope (`pv-app`). The production host must supply tenant propagation, RLS, and connection routing. The library-scope ports are production-ready.

---

## S3 -- Assumptions & Gaps

| # | Assumption / Gap | Impact |
|---|------------------|--------|
| A-1 | Rollup cells carry both `settledValue`/`pnl` and `forwardMarkValue` on the same row. The existing `RollupCell` record already has `forwardMarkValue`. Per ADR-002, `forwardMarkValue` is populated by calling `ForwardMarkService.computePortfolioMtm(...)` during rollup materialization (triggered by CurveTick via cache invalidation + targeted rollup recomputation). This spec assumes that pipeline exists or will exist. | If `forwardMarkValue` is never populated, L1/L2 forward data will be zero. |
| A-2 | The number of positions per portfolio per month is bounded at ~200 (per OQ-8). L3 and L4 portfolio-scoped queries are designed for this cardinality. | If a tenant has >500 positions per portfolio-month, cursor-based pagination is needed on L3. |
| A-3 | S6b `trade_interval_cache` rows are keyed by `(tenant_id, trade_leg_id, interval_start)`. Portfolio-scoped S6b queries require a two-step lookup: position IDs from S1, then trade-leg IDs, then S6b per trade-leg. No `portfolioId` denormalization on S6b (per OQ-10 -- avoid write-time cost). | Acceptable for ~200 positions. |
| A-4 | `MarketCalendar` service exists or will exist for CET/CEST day boundary computation. The dashboard queries accept `timezone` parameter (default `Europe/Berlin`) and compute UTC boundaries server-side. | If `MarketCalendar` does not exist, day-boundary computation must be implemented inline (utility method). |
| G-1 | **Resolved by ADR-002:** Forward marks are computed on demand by `ForwardMarkService`, not read from a persistent S5b store. For L4 forward day view, `ForwardMarkService.computeIntervalMarks(tenantId, positionId, dayStart, dayEnd)` is called per position. Monthly curve prices are Redis-cached with short TTL. Performance depends on Redis cache hit rate and S6b query speed, not on a persistent mark store. | Acceptable for ~200 positions per portfolio. Monitor Redis cache hit rate. |
| G-2 | **Open:** The functional spec (OQ-4) defers the decision on L4 portfolio-day netting (individual per position vs. netted). This spec designs for individual-per-position display (option a) as the default, with netting as a future enhancement. | No netting logic designed. |

---

## S4 -- Domain Model Additions

All new types are records in `pv-domain`. Pattern #3 (Value Object).

### 4.1 `PortfolioSummary` -- L1 portfolio card data

```
record PortfolioSummary(
    String portfolioId,
    String currency,
    BigDecimal realizedPnl,           // sum of rollup pnl for settled/transition periods
    BigDecimal unrealizedMtm,         // sum of rollup forwardMarkValue
    BigDecimal totalPortfolioValue,   // realizedPnl + unrealizedMtm
    BigDecimal settledNetMw,          // TWA of netMw from settled rollup cells
    BigDecimal settledNetMwh,         // sum of netMwh from settled rollup cells
    BigDecimal forwardNetMw,          // TWA of netMw from forward rollup cells
    BigDecimal forwardNetMwh,         // sum of netMwh from forward rollup cells
    Instant dataAsOf                  // max(computedAt) across contributing cells
)
```

Location: `pv-domain/port/service/dashboard/PortfolioSummary.java`

### 4.2 `PositionContribution` -- L3 trade-level per-position summary

```
record PositionContribution(
    UUID positionId,
    String tradeId,
    String tradeLegId,
    int tradeVersion,
    Instant deliveryStart,
    Instant deliveryEnd,
    BigDecimal quantity,              // contractual nominal from S1
    String volumeUnit,
    String deliveryPointId,
    String deliveryStatus,            // SETTLED | PARTIAL | FORWARD

    // Settled actuals (from S5a)
    BigDecimal settledMw,             // TWA
    BigDecimal settledMwh,            // sum
    BigDecimal avgPrice,              // volume-weighted avg
    BigDecimal settledValue,          // sum of amount
    BigDecimal marketValue,           // sum of marketAmount
    BigDecimal realizedPnl,           // sum of pnl

    // Forward forecast (from S6b + ForwardMarkService, per ADR-002)
    BigDecimal forwardMw,             // TWA of resolvedQty
    BigDecimal forwardMwh,            // sum of resolvedEnergy
    BigDecimal forwardMarkValue,      // computed by ForwardMarkService (S4 × S6b)
    BigDecimal unrealizedMtm,         // = forwardMarkValue

    String currency
)
```

Location: `pv-domain/port/service/dashboard/PositionContribution.java`

### 4.3 `DailyAggregate` -- L4 month view daily row

```
record DailyAggregate(
    Instant dayStart,                 // UTC boundary of the CET/CEST day
    Instant dayEnd,                   // UTC boundary
    String dayStatus,                 // SETTLED | TODAY | FORWARD
    int intervalCount,                // 92, 96, or 100 (DST-aware)

    // Settled data (null for FORWARD days)
    BigDecimal settledMw,
    BigDecimal settledMwh,
    BigDecimal avgPrice,
    BigDecimal settledValue,
    BigDecimal marketValue,
    BigDecimal realizedPnl,

    // Forward data (null for SETTLED days)
    BigDecimal forwardMw,
    BigDecimal forwardMwh,
    BigDecimal curvePrice,            // volume-weighted avg from ForwardMarkService
    BigDecimal forwardMarkValue,

    String currency
)
```

Location: `pv-domain/port/service/dashboard/DailyAggregate.java`

### 4.4 `ForwardIntervalDetail` -- L4 forward day view per-interval row

```
record ForwardIntervalDetail(
    Instant intervalStart,
    Instant intervalEnd,
    UUID positionId,                  // nullable for netted view
    String tradeLegId,                // nullable for netted view
    BigDecimal resolvedQty,           // MW from S6b
    BigDecimal resolvedEnergy,        // MWh from S6b
    BigDecimal multiplier,
    String seriesKey,
    BigDecimal evaluatedPrice,        // from ForwardMarkService (S4 curve + shaping)
    BigDecimal markValue,             // computed: evaluatedPrice × resolvedEnergy
    String curveId,                   // curve used for price evaluation
    long curveVersion,                // curve version at computation time
    String currency                   // from price expression evaluation
)
```

Location: `pv-domain/port/service/dashboard/ForwardIntervalDetail.java`

---

## S5 -- New / Modified Ports

### 5.1 New port: `DashboardQueryService` (Pattern #18)

Location: `pv-domain/port/service/DashboardQueryService.java`

This is a **read-only query service** facade. It does not publish events, does not write state, and does not require `UnitOfWork`. It composes reads from multiple repository ports and applies domain aggregation logic (FR-035).

```
interface DashboardQueryService {

    // --- L1: Portfolio Cards ---

    /** Q-1: Aggregate rollup cells across all delivery points for a portfolio.
     *  Groups by currency. Returns one PortfolioSummary per currency. */
    List<PortfolioSummary> portfolioSummaries(
        String tenantId,
        String portfolioId,
        Instant rangeStart,
        Instant rangeEnd,
        TimeGranularity rollupGranularity   // typically MONTHLY or YEARLY
    );

    // --- L2: Period Grid ---

    /** Q-1 variant: Return raw rollup cells for a portfolio across all
     *  delivery points. Clients render the grid directly. */
    List<RollupCell> rollupGrid(
        String tenantId,
        String portfolioId,
        Instant rangeStart,
        Instant rangeEnd,
        TimeGranularity granularity
    );

    // --- L3: Trade-Level View ---

    /** Q-2 + Q-9: Position contributions for a portfolio within a delivery range.
     *  Joins S1 positions with S5a settlement summaries, S6b forward volumes,
     *  and ForwardMarkService-computed marks per position (ADR-002). */
    List<PositionContribution> positionContributions(
        String tenantId,
        String portfolioId,
        Instant periodStart,
        Instant periodEnd
    );

    // --- L4: Month View ---

    /** Q-4: Daily aggregates for a position or portfolio within a month.
     *  Uses DAILY rollup cells (S7) when available, falls back to on-the-fly
     *  aggregation from S5a + ForwardMarkService + S6b. */
    List<DailyAggregate> dailyAggregates(
        String tenantId,
        String portfolioId,
        UUID positionId,            // nullable -- null means portfolio-scoped
        Instant monthStart,
        Instant monthEnd,
        String timezone              // default "Europe/Berlin"
    );

    // --- L4: Settled Day View ---

    /** Q-3 + Q-5: Settlement cells for a position or portfolio on a single day,
     *  optionally aggregated to a coarser sub-daily granularity. */
    List<SettlementCell> settledDayDetail(
        String tenantId,
        String portfolioId,
        UUID positionId,            // nullable
        Instant dayStart,
        Instant dayEnd,
        TimeGranularity subDailyGranularity  // MIN_15, MIN_30, HOURLY
    );

    // --- L4: Forward Day View ---

    /** Q-6 + Q-7: Forward interval detail for a position or portfolio
     *  on a single day, optionally aggregated. */
    List<ForwardIntervalDetail> forwardDayDetail(
        String tenantId,
        String portfolioId,
        UUID positionId,            // nullable
        Instant dayStart,
        Instant dayEnd,
        TimeGranularity subDailyGranularity  // MIN_15, MIN_30, HOURLY
    );
}
```

Justification for a single facade rather than extending existing query services: The dashboard queries are composite reads that span multiple subsystems (S1 + S4/ForwardMarkService + S5a + S6b + S7). Existing query services (`PositionQueryService`, `SettlementQueryService`, `RollupQueryService`) are single-subsystem. A composite service avoids N+1 API calls from controllers and keeps FR-035 aggregation logic server-side.

### 5.2 Modified port: `RollupRepository` (Pattern #18)

New method on existing interface:

```
/** Q-1: Rollup cells for a portfolio across ALL delivery points. */
List<RollupCell> findByPortfolio(String tenantId,
                                  String portfolioId,
                                  Instant rangeStart,
                                  Instant rangeEnd,
                                  TimeGranularity granularity);
```

This is option (a) from Q-1 in the functional spec. Preferred over allowing `deliveryPointId = null` because it avoids ambiguity on the existing method signature and maps to a distinct SQL query with a different index path.

### 5.3 Modified port: `PositionLedgerRepository` (Pattern #18)

New method on existing interface:

```
/** Q-2: Current-knowledge ACTIVE positions for a portfolio within a delivery range. */
List<PositionLedgerEntry> findByPortfolioAndDeliveryRange(
    String tenantId,
    String portfolioId,
    Instant deliveryStart,
    Instant deliveryEnd);
```

This is option (a) from Q-2 in the functional spec. Preferred over application-layer filtering because portfolios with hundreds of trades across other portfolios would transfer unnecessary data.

### 5.4 Modified port: `TradeIntervalCache` (Pattern #18)

New method on existing interface:

```
/** Q-7: S6b records for multiple trade-legs within an interval range. */
List<TradeIntervalRecord> getForTradeLegIds(String tenantId,
                                              List<String> tradeLegIds,
                                              Instant rangeStart,
                                              Instant rangeEnd);
```

This avoids N+1 calls for the `getForTradeLeg()` method when L3/L4 queries involve multiple positions. The adapter can issue a single SQL query with an `IN` clause.

### 5.4a Modified port: `SettlementCellRepository` (Pattern #18)

New method on existing interface:

```
/** Bulk-fetch settlement cells for multiple positions within a range.
 *  Replaces per-position findByPosition() N+1 pattern for L3 queries.
 *  Adapter issues a single SQL query with position_id IN (...). */
List<SettlementCell> findByPositionIds(String tenantId,
                                        List<UUID> positionIds,
                                        Instant rangeStart,
                                        Instant rangeEnd);
```

This is the bulk counterpart to the existing `findByPosition()`. For L3 with ~200 positions, it collapses 200 indexed queries into 1. The adapter batches the `IN` clause into chunks of 100 to stay within PostgreSQL parameter limits (same pattern as `getForTradeLegIds()`). The service groups the returned cells by `positionId` in application memory and applies FR-035 aggregation per position.

### 5.5 Dependency: `ForwardMarkService` (ADR-002)

Per ADR-002, the `ForwardMarkStore` port is **eliminated**. Forward marks are computed on demand by `ForwardMarkService` (defined in `pv-domain/port/service/ForwardMarkService.java`). The dashboard's `DashboardQueryService` depends on `ForwardMarkService` for:

- **L3** `positionContributions()`: calls `ForwardMarkService.computeMonthlyMark(tenantId, positionId, monthStart, monthEnd)` per position to get `forwardMarkValue`.
- **L4** `forwardDayDetail()`: calls `ForwardMarkService.computeIntervalMarks(tenantId, positionId, dayStart, dayEnd)` per position to get per-interval `evaluatedPrice` and `markValue`.
- **L4** `dailyAggregates()` for forward days: calls `ForwardMarkService.computeMonthlyMark(...)` scoped to each day's boundaries.

No new methods are added to `ForwardMarkService` — the existing ADR-002 interface serves all dashboard needs. The dashboard is a consumer, not a modifier, of this service.

---

## S6 -- Adapters

### 6.1 `JpaRollupRepository` -- new `findByPortfolio()` (Pattern #18)

Location: `pv-persistence/adapter/JpaRollupRepository.java`

New native query: same as existing `findByRange()` but without the `delivery_point_id` filter. Uses index `(tenant_id, portfolio_id, granularity, interval_start)`.

### 6.2 `JpaPositionLedgerRepository` -- new `findByPortfolioAndDeliveryRange()` (Pattern #18)

Location: `pv-persistence/adapter/JpaPositionLedgerRepository.java`

JPQL query filtering on `tenantId`, `portfolioId`, delivery range overlap, `knownTo IS NULL` (current knowledge), `status = 'ACTIVE'`.

### 6.3 `JpaTradeIntervalCache` -- new `getForTradeLegIds()` (Pattern #18)

Location: `pv-persistence/adapter/JpaTradeIntervalCache.java`

JPQL query with `tradeLegId IN :tradeLegIds` clause. Uses existing index `idx_tic_trade_leg_time`. For >100 trade-leg IDs, the adapter should batch into chunks of 100 to avoid PostgreSQL parameter limits.

### 6.3a `JpaSettlementCellRepository` -- new `findByPositionIds()` (Pattern #18)

Location: `pv-persistence/adapter/JpaSettlementCellRepository.java`

JPQL query with `positionId IN :positionIds` clause. Uses existing index `(tenant_id, position_id, interval_start)`. For >100 position IDs, the adapter batches into chunks of 100 (same pattern as `getForTradeLegIds()`). Returns raw `SettlementCell` entities; FR-035 aggregation (TWA for MW, sum for MWh/amounts, volume-weighted avg for prices) is applied in the service layer per position.

### 6.4 `ForwardMarkService` -- no new adapter needed (ADR-002)

Per ADR-002, `ForwardMarkService` is a domain service (not a repository adapter). Its implementation evaluates price expressions against S4 curves and multiplies by S6b volumes. It caches evaluated monthly prices in Redis. The dashboard calls `ForwardMarkService` methods directly — no adapter work needed beyond what ADR-002 specifies.

### 6.5 `DefaultDashboardQueryService` -- new service implementation

Location: `pv-domain/service/DefaultDashboardQueryService.java`

Implements `DashboardQueryService`. Constructor-injected dependencies (all via `@jakarta.inject.Inject`):

- `RollupRepository` -- for L1, L2, L4 month view (DAILY rollups)
- `PositionLedgerRepository` -- for L3 position lookup
- `SettlementCellRepository` -- for L3 settled summaries, L4 settled day view
- `ForwardMarkService` -- for L3 forward mark computation, L4 forward day view (ADR-002)
- `TradeIntervalCache` -- for L3 forward volume, L4 forward day view
- `NumericPrecision` -- for FR-035 aggregation arithmetic

Key domain logic in this service:

1. **L1 `portfolioSummaries()`**: Reads rollup cells via `findByPortfolio()`, partitions into settled/forward based on `periodEnd` vs. `Instant.now()`, groups by currency, applies TWA for MW and sum for MWh/amounts per FR-035.

2. **L3 `positionContributions()`**: Hybrid bulk-fetch + application-layer aggregation. Three bulk queries followed by in-memory grouping and FR-035 aggregation. No per-position queries.

   - Step 1: `PositionLedgerRepository.findByPortfolioAndDeliveryRange()` → position metadata + list of position IDs and trade-leg IDs. **1 SQL query.**
   - Step 2 (parallel bulk fetches — no dependencies between them):
     - 2a: `SettlementCellRepository.findByPositionIds(tenantId, positionIds, rangeStart, rangeEnd)` → all S5a (Settlement Cells) for all positions in one query. Group by `positionId` in memory, then apply FR-035 rules per position (TWA for MW, sum for MWh/amounts, volume-weighted avg for prices). **1 SQL query** (replaces 200 per-position queries).
     - 2b: `TradeIntervalCache.getForTradeLegIds(tenantId, tradeLegIds, rangeStart, rangeEnd)` → all S6b (Trade Interval Cache) records in one query. Group by `tradeLegId` → `positionId` in memory, aggregate per position with FR-035 rules. **1 SQL query.**
     - 2c: `ForwardMarkService.computeMonthlyMark(tenantId, positionId, monthStart, monthEnd)` per position with undelivered intervals. Monthly curve prices are Redis-cached, so each call is O(1) on cache hit (~sub-ms). **~N Redis lookups** (not SQL). Returns `forwardMarkValue`, `totalMwh`, `avgPrice`, `curveId`, `curveVersion`.
   - Step 3: Merge results per position. Derive `deliveryStatus`: SETTLED if only S5a data exists for the month; FORWARD if only S6b data exists (all intervals undelivered); PARTIAL if both exist.

   **Round-trip budget for 200 positions:** 3 SQL queries + ~200 Redis cache lookups ≈ 20-50ms total (vs. ~402 round-trips / 200-500ms in a naive per-position design).

3. **L4 `settledDayDetail()` with sub-daily aggregation (Q-5)**: Reads 15-min S5a cells, then if `subDailyGranularity` is MIN_30 or HOURLY, groups by target bucket boundaries and applies FR-035 aggregation: MW = TWA (weighted by interval duration), MWh = sum, amount/marketAmount/pnl = sum, price = settledValue / totalMwh, marketPrice = marketValue / totalMwh. All arithmetic via `NumericPrecision`.

4. **L4 `forwardDayDetail()` with sub-daily aggregation**: Calls `ForwardMarkService.computeIntervalMarks(tenantId, positionId, dayStart, dayEnd)` per position. Returns `IntervalMark` records with `evaluatedPrice`, `markValue`, `resolvedQty`, `resolvedEnergy`. Same grouping logic for sub-daily aggregation (TWA for MW, sum for MWh/markValue, volume-weighted avg for price).

5. **L4 `dailyAggregates()`**: Prefers DAILY rollup cells from S7 if materialized. Falls back to on-the-fly aggregation from S5a (for settled days) and ForwardMarkService-computed marks (for forward days), grouped by CET/CEST day boundaries. Day status derived from comparing day boundaries against `Instant.now()`.

**Design rationale — why not a single SQL join across S1+S5a+S6b?** A `LATERAL JOIN` collapsing all three into one query would minimize round-trips to 1, but pushes FR-035 aggregation rules (TWA, volume-weighted average) into SQL. These rules are domain logic that must remain testable, auditable, and consistent with the rest of the platform. The hybrid approach (3 bulk fetches + Java aggregation) keeps domain logic in `pv-domain`, is trivially unit-testable with hand-mocked ports, and avoids coupling S1+S5a+S6b in a single adapter query. The 3 SQL queries are independent and can be parallelized if needed (virtual threads or `CompletableFuture`).

---

## S7 -- Data Model Impact

### 7.1 S7 Rollup Cell table -- no schema change

The existing `volume_series.rollup_cell` table already has `forward_mark_value`, `granularity`, and all necessary columns. The `granularity` column already accepts `'DAILY'` as a valid value (it is stored as a varchar matching `TimeGranularity.name()`). The `DAILY` value simply has no materialized data yet.

**New index required:**

```
idx_rollup_portfolio_granularity_time
  ON volume_series.rollup_cell (tenant_id, portfolio_id, granularity, interval_start)
```

Purpose: supports `findByPortfolio()` (Q-1) which queries across all delivery points for a portfolio. The existing unique constraint is `(tenant_id, delivery_point_id, portfolio_id, interval_start, granularity, is_peak)` -- this already covers the existing `findByRange()` query which includes `delivery_point_id`. The new index drops `delivery_point_id` for the portfolio-wide query.

### 7.2 S1 Position Ledger -- no schema change

The existing `position_ledger_entry` table has `portfolio_id`, `tenant_id`, `delivery_start`, `delivery_end`, `known_to`, `status`.

**New index required:**

```
idx_ple_portfolio_delivery
  ON position_ledger.position_ledger_entry (tenant_id, portfolio_id, delivery_start, delivery_end)
  WHERE known_to IS NULL AND status = 'ACTIVE'
```

Purpose: supports `findByPortfolioAndDeliveryRange()` (Q-2). Partial index on current-knowledge active entries only.

### 7.3 S5a, S6b -- no schema changes

No denormalization of `portfolioId` onto S5a or S6b (per OQ-10 decision). The two-step query pattern (S1 for position IDs, then per-position queries) is acceptable for the expected cardinality (~200 positions per portfolio-month).

S5b (forward mark persistent storage) is **eliminated** per ADR-002. No `forward_mark` table or `ForwardMarkStore` port exists. Forward marks are computed on demand by `ForwardMarkService`.

### 7.4a S5c EOD Snapshot -- new table (ADR-002)

The S5c `forward_mark_snapshot` table (defined in ADR-002) stores daily end-of-day forward MtM snapshots at the grain of `(position × delivery-month × business-date)`. This table is **not directly queried by the dashboard** — it serves as-of queries, EMIR daily valuation, and PnL attribution. The schema is defined in ADR-002 and its migration is managed separately from this spec.

### 7.4 Flyway migration outline

One migration file:

- **V<next>__dashboard_indexes.sql**: Creates the two new indexes (S7.1 and S7.2). No DDL for new tables. Implementation-engineer determines the version number.

---

## S8 -- Event Flow

**No new events.** The dashboard is a pure read feature. It does not trigger any write operations, event publications, or state changes.

The DAILY rollup materialization (S7) is an extension of the existing materialization pipeline. It is triggered by the same events as WEEKLY/MONTHLY/YEARLY rollups:

- `SettlementComputed` -> `SettlementComputedConsumer` -> `RollupMaterializationService.materializeForPosition()` -- which already iterates `PORTFOLIO_GRANULARITIES`. DAILY is added to this list.

**Modified constant only:** `RollupMaterializationService.PORTFOLIO_GRANULARITIES` changes from `[WEEKLY, MONTHLY, YEARLY]` to `[DAILY, WEEKLY, MONTHLY, YEARLY]`.

No new Kafka topics. No new outbox rows. No new consumers.

---

## S9 -- Guice Wiring

### 9.1 `DomainModule` (pv-guice)

New binding:

```
bind(DashboardQueryService.class)
    .to(DefaultDashboardQueryService.class)
    .in(Singleton.class);
```

### 9.2 `DomainServiceConfig` (pv-app) -- Spring bridge

New `@Bean` method:

```
@Bean
public DashboardQueryService dashboardQueryService(Injector injector) {
    return injector.getInstance(DashboardQueryService.class);
}
```

No `new` call. Delegates to `injector.getInstance()` per D-13.

---

## S10 -- Cross-Cutting

### 10.1 Tenant handling (P1, Pattern #32)

Every method on `DashboardQueryService` accepts `tenantId` as the first parameter. Every downstream repository, cache, and `ForwardMarkService` call passes `tenantId`. In the production host, RLS policies on `rollup_cell`, `position_ledger_entry`, `settlement_cell`, `trade_interval_cache` enforce tenant isolation at the database level. `ForwardMarkService` inherits tenant scoping from its S4 and S6b data sources.

### 10.2 Bitemporal invariants

- S1 queries use `knownTo IS NULL AND status = 'ACTIVE'` to read current knowledge.
- S5a queries read current cells (no bitemporal filter needed until S5a gains bitemporality; when it does, add `knownTo IS NULL`).
- Forward marks: computed on demand by ForwardMarkService (ADR-002). No persistent store, no bitemporal concern.
- S6b is a rebuildable cache -- no bitemporal concern (D-12).
- S7 rollup cells are upserted, not versioned bitemporally. `versionHash` tracks staleness.
- **No bitemporal entities are mutated by this feature.** All access is read-only.

### 10.3 Transaction boundaries

All dashboard queries are **read-only**. They require a transaction for JPA entity loading but do not write. In `pv-app`, the `TransactionalExecutor` wraps the call. In a production host, read-only transactions should route to the reader DataSource via `DataSourceRouter` (Pattern #22, #28 CQRS).

### 10.4 Cache invalidation

The dashboard reads materialized data (S7 rollup cells) and computes forward marks on demand (ForwardMarkService). Cache invalidation is handled by existing/ADR-002 pipelines:

- S7 rollup cells (settled): invalidated/refreshed by `RollupMaterializationService` on `SettlementComputed` events.
- S7 rollup cells (forward): `forwardMarkValue` recomputed by `ForwardMarkService` on `CurveTick` events via cache invalidation + targeted rollup recomputation (ADR-002).
- ForwardMarkService Redis cache: evaluated monthly prices cached with short TTL, invalidated on `CurveTick` for affected curve IDs (ADR-002).
- S6b trade interval cache: rebuilt by `TradeIntervalCacheRebuilder` on `VolumeSuperseded` events.

No new cache keys or invalidation triggers are introduced by the dashboard itself.

---

## S10a -- Performance Profile

### Pagination strategy

| Endpoint | Expected rows | Strategy | Default page | Max page |
|----------|--------------|----------|-------------|----------|
| L1 `portfolioSummaries` | 1--5 per portfolio (one per currency) | No pagination | N/A | N/A |
| L2 `rollupGrid` | 12--104 (monthly = 12-24, weekly = 52-104) | No pagination; bounded by date range | N/A | N/A |
| L3 `positionContributions` | 10--200 per portfolio-month | Offset-based (bounded set) | 50 | 200 |
| L4 `dailyAggregates` | 28--31 per month | No pagination | N/A | N/A |
| L4 `settledDayDetail` | 24--100 per day (sub-daily) | No pagination; max 100 rows | N/A | N/A |
| L4 `forwardDayDetail` | 24--100 per day per position | No pagination for single position; offset for portfolio scope | 100 | 500 |

### Response size budget

- L1: <5KB (summary records).
- L2: <50KB (up to 104 rollup cell records).
- L3: <100KB (up to 200 position contribution records).
- L4 day view: <50KB (up to 100 interval records).
- L4 month view: <20KB (31 daily aggregate records).

All within the <100KB target for interactive views.

### Query performance

- **L1/L2** queries hit S7 `rollup_cell` with the new `idx_rollup_portfolio_granularity_time` index. Single index scan, no join.
- **L3** uses bulk-fetch + application-layer aggregation (3 SQL queries total):
  - One index scan on S1 (Position Ledger) `idx_ple_portfolio_delivery` (partial index, highly selective). **1 query.**
  - Bulk S5a (Settlement Cells) query: `findByPositionIds()` with `position_id IN (...)`, using existing `(tenant_id, position_id, interval_start)` index. Returns all cells for all positions in one round-trip; grouped by `positionId` in memory. **1 query** (replaces 200 per-position queries).
  - Bulk S6b (Trade Interval Cache) query: `getForTradeLegIds()` with `trade_leg_id IN (...)`. **1 query.**
  - ForwardMarkService: `computeMonthlyMark()` per position. Monthly curve prices are Redis-cached; computation is `O(1)` on cache hit (~sub-ms). For 200 positions, ~200 Redis cache lookups. **No SQL — Redis only.**
  - **Total for 200 positions: 3 SQL queries + ~200 Redis lookups ≈ 20-50ms.** FR-035 aggregation (TWA, volume-weighted avg, sum) applied in Java per position.
- **L4 day view**: single-position queries call `ForwardMarkService.computeIntervalMarks()` which loads S6b intervals + evaluates (cached) monthly price + applies shaping. Expected latency: 1-5ms per position with warm cache, up to 50ms cold.

### Redis cache

The dashboard leverages the `ForwardMarkService` Redis cache for evaluated monthly prices (ADR-002). Cache keys are `(expressionId, curveVersionHash, month)` with short TTL (~60s), invalidated on CurveTick. No new Redis cache keys are introduced by the dashboard itself — it consumes the cache indirectly through `ForwardMarkService`.

### Connection pooling

No new DataSource or connection path. All queries use the existing writer EntityManager (in the simulator) or the reader EntityManager (in production, via `DataSourceRouter`). Default pool sizing is sufficient.

---

## S10b -- Real-Time Push

**Not applicable for v1.0.** Per OQ-7 in the functional spec, the refresh model is poll-based. The dashboard reads materialized data on each request.

When a real-time push path is introduced in a future version:
- It should use SSE for one-way server-to-client position/MtM updates.
- The Kafka topic source would be `posval.SettlementComputed` (for settlement updates) and `posval.RollupRefreshed` (for forward MtM updates, emitted after CurveTick → rollup recomputation per ADR-002).
- Tenant isolation on the push path must filter events by `tenantId` before sending.
- Graceful degradation: REST polling via TanStack Query `refetchInterval` as fallback.

---

## S10c -- DST Handling

### Time zone convention

- All timestamps in API responses are UTC (`Instant`).
- The `dailyAggregates()` and day-view endpoints accept an optional `timezone` parameter (default `Europe/Berlin`) so the server can compute CET/CEST day boundaries correctly.
- The `DailyAggregate.dayStart` and `DailyAggregate.dayEnd` fields are UTC instants representing the correct boundaries for the CET/CEST day.

### DST transition days

- **Spring-forward** (last Sunday of March): 23-hour day = 92 quarter-hour intervals. `DailyAggregate.intervalCount = 92`. L4 day view returns 92 rows at MIN_15, 46 at MIN_30, 23 at HOURLY.
- **Fall-back** (last Sunday of October): 25-hour day = 100 quarter-hour intervals. `DailyAggregate.intervalCount = 100`. L4 day view returns 100 rows at MIN_15, 50 at MIN_30, 25 at HOURLY.
- The sub-daily aggregation in `DefaultDashboardQueryService` groups by UTC-based bucket boundaries. On fall-back day, the two occurrences of 02:00 CET are distinct UTC intervals and aggregate into distinct buckets.

### Day boundary computation

The service computes day boundaries as:
```
ZonedDateTime dayStartLocal = localDate.atStartOfDay(ZoneId.of(timezone));
Instant dayStartUtc = dayStartLocal.toInstant();
Instant dayEndUtc = dayStartLocal.plusDays(1).toInstant();
```

This correctly produces:
- CET winter day: 23:00 UTC to 23:00 UTC (24h)
- CEST summer day: 22:00 UTC to 22:00 UTC (24h)
- Spring-forward: 23:00 UTC to 22:00 UTC (23h)
- Fall-back: 22:00 UTC to 23:00 UTC (25h)

### Gate closure alignment

Not directly applicable (read-only dashboard). No gate closure filters.

---

## S11 -- Regulatory Impact

| Regulation | Impact |
|------------|--------|
| **REMIT** (Regulation 1227/2011) | None. The dashboard is a read-only view of existing data. It does not generate, modify, or report any data. Timestamps displayed are UTC-convertible for cross-reference with REMIT reports. |
| **EMIR** | **Labeling requirement.** The dashboard displays ForwardMarkService-computed marks (indicative, computed on demand from current S4 × S6b per ADR-002) -- NOT S5c EOD snapshots (official EMIR Art. 9 daily valuation). The API response must include a field or metadata indicating that MtM values are "indicative current marks" not "official EOD marks." The DTO design includes this distinction. |
| **MiFID II** (RTS 22) | None. L3 trade-level view displays `tradeId` and `tradeLegId` to support cross-reference with RTS 22 transaction reports, but does not itself report. |

No new regulatory obligations arise from this feature.

---

## S12 -- Testing Strategy

### 12.1 Unit tests (`*Test.java`)

Location: `pv-domain/src/test/java/.../service/DefaultDashboardQueryServiceTest.java`

- Hand-mock all repository ports and services (`RollupRepository`, `PositionLedgerRepository`, `SettlementCellRepository`, `ForwardMarkService`, `TradeIntervalCache`).
- Test FR-035 aggregation logic: TWA for MW, sum for MWh, volume-weighted average for prices. Use `DefaultNumericPrecision` for scale assertions.
- Test sub-daily aggregation: verify that 15-min cells correctly aggregate to 30-min (2 cells per bucket) and 60-min (4 cells per bucket).
- Test DST handling: provide 92 cells for spring-forward day, verify correct aggregation. Provide 100 cells for fall-back day, verify the duplicate hour aggregates into distinct buckets.
- Test `deliveryStatus` derivation: SETTLED when only S5a data exists, FORWARD when only S6b/ForwardMarkService data exists, PARTIAL when both.
- Test L3 bulk-fetch grouping: mock `findByPositionIds()` returning cells for 3 positions interleaved; verify service correctly groups by `positionId` before applying per-position FR-035 aggregation.
- Test multi-currency grouping for `portfolioSummaries()`.
- Test empty data scenarios: no rollup cells, no settlement cells, ForwardMarkService returns null/empty (curve or volume unavailable).

### 12.2 Integration tests (`*IT.java`)

Location: `pv-integration-tests/src/test/java/.../DashboardQueryIT.java`

- Testcontainers PostgreSQL 16. NOT H2.
- Insert S1 position ledger entries, S5a settlement cells, and S7 rollup cells via the real JPA adapters.
- Query via `DefaultDashboardQueryService` composed with real JPA adapters.
- Verify that:
  - `findByPortfolio()` returns rollup cells across delivery points.
  - `findByPortfolioAndDeliveryRange()` returns only ACTIVE, current-knowledge entries for the specified portfolio.
  - `findByPositionIds()` returns cells for all requested positions in one query; returns empty list for unknown position IDs; respects tenant isolation.
  - Sub-daily aggregation produces correct results against real data.
  - New indexes are used (verify via `EXPLAIN ANALYZE` if feasible).
  - DAILY rollup materialization produces correct cells.

### 12.3 Contract tests

- `RollupRepository.findByPortfolio()` -- verify contract between port and JPA adapter: tenant isolation, granularity filter, date range overlap semantics.
- `PositionLedgerRepository.findByPortfolioAndDeliveryRange()` -- verify partial index usage, current-knowledge filter, ACTIVE status filter.
- `SettlementCellRepository.findByPositionIds()` -- verify bulk query returns same results as N individual `findByPosition()` calls. Verify >100 position IDs triggers batching. Verify tenant isolation.
- `TradeIntervalCache.getForTradeLegIds()` -- verify bulk query returns same results as N individual `getForTradeLeg()` calls.
- `ForwardMarkService` integration: verify `computeIntervalMarks()` returns correct `evaluatedPrice × resolvedEnergy = markValue` for known test data (mock S4 curve + S6b volumes).

---

## S13 -- Constraint Compatibility

| Constraint | Status |
|------------|--------|
| **D-1** (Ledger grain = trade-leg x delivery-month) | Compatible. L3 reads position ledger entries at their native grain. No interval fan-out in S1. |
| **D-2** (Price = expression reference) | Not applicable. Dashboard does not evaluate prices. |
| **D-3** (Forward marks ephemeral) | Compatible. Per ADR-002, forward marks are computed on demand by ForwardMarkService — even more ephemeral than before (not stored at all). S5c EOD snapshots provide daily as-of marks but are not queried by this dashboard. |
| **D-11** (Unified volume) | Not applicable. Dashboard reads pre-resolved volumes from S6b. |
| **D-12** (S6b optional, rebuildable) | Compatible. Dashboard reads S6b as a cache. If S6b is empty, forward volume data is unavailable -- the service returns null/zero for forward fields. |
| **D-13** (Library-first, Spring-free) | Compatible. All new ports (`DashboardQueryService`), value objects (`PortfolioSummary`, etc.), and the service implementation (`DefaultDashboardQueryService`) reside in `pv-domain`. No Spring types. REST controllers and DTOs reside in `pv-app` only. |
| **D-14** (Simulator patterns in pv-app only) | Compatible. No hardcoded tenant IDs, no in-memory caches, no `hbm2ddl` in library modules. DTOs and REST controllers are in `pv-app`. |
| **Bitemporal invariant** | Compatible. No bitemporal entities are mutated. All reads use current-knowledge predicates. |
| **Outbox (Pattern #24)** | Not applicable. No events emitted. |
| **Idempotent consumers (Pattern #26)** | Not applicable. No consumers introduced. |
| **Multi-tenancy (P1, Pattern #32)** | Compatible. Every query method accepts `tenantId`. Every repository call passes `tenantId`. |
| **NumericPrecision (S5.0)** | Compatible. All aggregation arithmetic in `DefaultDashboardQueryService` uses `NumericPrecision` for scale and rounding. No raw `BigDecimal` arithmetic. |

---

## S14 -- Open Items

| # | Item | Blocker?         | Owner |
|---|------|------------------|-------|
| OI-1 | **`ForwardMarkService` implementation (ADR-002).** The `ForwardMarkService` port must be implemented before the dashboard can display forward data. Implementation evaluates price expressions against S4 curves, multiplies by S6b volumes, and caches evaluated monthly prices in Redis. The dashboard depends on `computeMonthlyMark()` (L3) and `computeIntervalMarks()` (L4). | Yes (dependency) | implementation-engineer (ADR-002 scope) |
| OI-2 | **`MarketCalendar` availability.** Day boundary computation in `dailyAggregates()` uses `ZonedDateTime` directly. If a `MarketCalendar` service exists with richer logic (half-holidays, market-specific calendars), the dashboard service should delegate to it. If not, inline `ZonedDateTime` computation is acceptable for CET/CEST. | Yes              | implementation-engineer |
| OI-3 | **L3 N+1 query concern — RESOLVED.** Promoted from "monitor" to default design. `SettlementCellRepository.findByPositionIds()` bulk-fetches all S5a cells for all positions in 1 SQL query (§5.4a). L3 `positionContributions()` now uses 3 bulk SQL queries + ~N Redis lookups instead of ~N+2 per-position queries (§6.5). FR-035 aggregation remains in Java. | Resolved         | implementation-engineer |
| OI-4 | **DAILY rollup storage impact.** Adding DAILY to the materialization pipeline increases `rollup_cell` row count by approximately 10x vs. MONTHLY-only (31 days per month). For 200 tenants x 300 positions x 24 months x 31 days x 2 (peak/off-peak), this is ~89M rows. Verify partition strategy on `rollup_cell` accommodates this volume. | Yes              | solutions-architect |
| OI-5 | **Staleness endpoint (AC-L1-08).** Deferred from this spec. With ADR-002, staleness is determined by comparing the S7 rollup cell's curve version against the current S4 curve version. A future service should expose this comparison. The dashboard API exposes the rollup cell's `versionHash` and computation timestamp so the UI can display it. | Yes              | solutions-architect (follow-up spec) |
| OI-6 | **`forwardMarkValue` population on rollup cells — RESOLVED by ADR-002.** Per ADR-002, `forwardMarkValue` is populated by calling `ForwardMarkService.computePortfolioMtm(...)` during rollup materialization. The CurveTick handler invalidates Redis cache, identifies affected rollup cells via the dependency index (FR-103), and recomputes `forwardMarkValue` for each affected cell. This applies to all granularities (DAILY, WEEKLY, MONTHLY, YEARLY). The `ForwardMarkJob` is replaced by the CurveTick cache invalidation + rollup recomputation pipeline. | Resolved         | solutions-architect (ADR-002) |
| OI-7 | **Pagination for L3 `positionContributions()`.** The spec designs for offset-based pagination (bounded set of ~200). If tenant profiling reveals portfolios with >500 positions, cursor-based pagination (keyset on `positionId`) should be adopted. | Yes              | implementation-engineer |

---

## Appendix A: REST Endpoints (pv-app simulator scope)

All endpoints are in `pv-app/controller/DashboardController.java`. All delegate to `DashboardQueryService` obtained via `injector.getInstance()` per D-13.

### A.1 L1 -- Portfolio Cards

```
GET /api/dashboard/portfolios/{portfolioId}/summary
  ?tenantId=
  &rangeStart=          (ISO-8601 Instant)
  &rangeEnd=            (ISO-8601 Instant)
  &granularity=MONTHLY  (optional, default MONTHLY)

Response: ApiResponse<List<PortfolioSummaryDto>>
```

### A.2 L2 -- Period Grid

```
GET /api/dashboard/portfolios/{portfolioId}/rollups
  ?tenantId=
  &rangeStart=
  &rangeEnd=
  &granularity=MONTHLY  (DAILY | WEEKLY | MONTHLY | YEARLY)

Response: ApiResponse<List<RollupCellDto>>
```

Reuses existing `RollupCellDto` from `pv-app/dto/`.

### A.3 L3 -- Trade-Level View

```
GET /api/dashboard/portfolios/{portfolioId}/positions
  ?tenantId=
  &periodStart=
  &periodEnd=
  &offset=0             (optional)
  &limit=50             (optional, max 200)

Response: ApiResponse<List<PositionContributionDto>>
```

### A.4 L4 -- Month View

```
GET /api/dashboard/portfolios/{portfolioId}/daily
  ?tenantId=
  &monthStart=
  &monthEnd=
  &positionId=          (optional, null = portfolio-scoped)
  &timezone=Europe/Berlin  (optional)

Response: ApiResponse<List<DailyAggregateDto>>
```

### A.5 L4 -- Settled Day View

```
GET /api/dashboard/settlements/day
  ?tenantId=
  &portfolioId=
  &positionId=          (optional, null = portfolio-scoped)
  &dayStart=
  &dayEnd=
  &granularity=MIN_15   (MIN_15 | MIN_30 | HOURLY)

Response: ApiResponse<List<SettlementCellDto>>
```

Reuses existing `SettlementCellDto` for MIN_15. For MIN_30 and HOURLY, settlement cells are aggregated server-side and returned in the same DTO shape (the aggregated values replace the per-interval values).

### A.6 L4 -- Forward Day View

```
GET /api/dashboard/forward/day
  ?tenantId=
  &portfolioId=
  &positionId=          (optional)
  &dayStart=
  &dayEnd=
  &granularity=MIN_15   (MIN_15 | MIN_30 | HOURLY)

Response: ApiResponse<List<ForwardIntervalDetailDto>>
```

### DTO locations

All new DTOs in `pv-app/dto/dashboard/`:
- `PortfolioSummaryDto` -- maps from `PortfolioSummary`
- `PositionContributionDto` -- maps from `PositionContribution`
- `DailyAggregateDto` -- maps from `DailyAggregate`
- `ForwardIntervalDetailDto` -- maps from `ForwardIntervalDetail`

Each DTO has a `static from(DomainRecord)` factory method per existing pattern.

---

## Appendix B: DAILY Rollup Materialization Change

The change to `RollupMaterializationService` is minimal:

```
// Before
private static final List<TimeGranularity> PORTFOLIO_GRANULARITIES = List.of(
    TimeGranularity.WEEKLY, TimeGranularity.MONTHLY, TimeGranularity.YEARLY);

// After
private static final List<TimeGranularity> PORTFOLIO_GRANULARITIES = List.of(
    TimeGranularity.DAILY, TimeGranularity.WEEKLY, TimeGranularity.MONTHLY, TimeGranularity.YEARLY);
```

The existing `materialize()` method already supports `TimeGranularity.DAILY` via the `truncateToPeriod()` and `advancePeriod()` switch cases, which already handle `DAILY`. No other changes needed to the materialization logic.

**Performance note:** DAILY materialization for a single `materializeForPosition()` call widens the range to YEARLY boundaries and re-materializes all four granularities. For DAILY, this means materializing up to 365 rollup cells per (deliveryPoint, portfolio) per year. This is acceptable given the existing per-event trigger pattern. If batch performance becomes a concern, the materialization range for DAILY could be narrowed to the affected month rather than the full year.

---

## Appendix C: Pattern Catalog Cross-Reference

| Design Element | Pattern # | Catalog Location |
|---------------|-----------|-----------------|
| `DashboardQueryService` (port interface) | #18 (Repository Port + Adapter) | ADR-001 S2.5 |
| `DefaultDashboardQueryService` (domain service) | #18 adapter, implements port | ADR-001 S2.5 |
| `PortfolioSummary`, `PositionContribution`, `DailyAggregate`, `ForwardIntervalDetail` | #3 (Value Object, Java record) | ADR-001 S2.1, ADR-002 (IntervalMark, MonthlyMark) |
| `RollupRepository.findByPortfolio()` | #18 (Repository) | ADR-001 S2.5 |
| `PositionLedgerRepository.findByPortfolioAndDeliveryRange()` | #18 (Repository) | ADR-001 S2.5 |
| Sub-daily aggregation logic | #9 (Strategy -- aggregation rules) | ADR-001 S2.3 |
| FR-035 TWA/sum arithmetic | Uses `NumericPrecision` port (S5.0) | ADR-001 S2.8 |
| Guice binding in `DomainModule` | Guice wiring pattern | ADR-001 S3.1 |
| Spring bridge in `DomainServiceConfig` | D-13 bridge pattern | ADR-001 S1.1 |
| Tenant handling on all queries | #32 (`@TenantAware` / `tenantId` parameter) | ADR-001 S2.8 |
| Read-only transaction routing | #22 (Dual DataSource), #28 (CQRS implicit) | ADR-001 S2.5, S2.6 |

---

*Hand-off: implementation-engineer for code, code-reviewer for D-1..D-14 verification.*
