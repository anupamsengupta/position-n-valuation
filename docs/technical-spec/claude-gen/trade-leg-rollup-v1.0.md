# Technical Specification -- Trade-Leg Rollup Materialization v1.0

## S1 -- Metadata & Status

| Field             | Value                                                                                   |
|-------------------|-----------------------------------------------------------------------------------------|
| Author            | solutions-architect                                                                     |
| Status            | DRAFT                                                                                   |
| Version           | 1.0                                                                                     |
| Date              | 2026-08-17                                                                              |
| Depends On        | functional-spec-position-valuation-v1.0 (FR-035, FR-090), ADR-001 (Pattern Catalog), ADR-002 (Forward Mark Strategy), position-pnl-dashboard-v1.0 (L3 query design) |
| Layer             | library-scope (`pv-domain`, `pv-persistence`, `pv-guice`)                               |
| Subsystems Touched| S5a (Settlement Cells -- read), S6b (Trade Interval Cache -- read), S7 (Rollups -- new table + write), S1 (Position Ledger -- read), S4 (Market Data -- read via ForwardMarkService) |

**Note:** Five subsystems are touched. Four are read-only; only S7 is mutated. The cross-cutting risk is acceptable because the new materialization is a strict extension of the existing S7 pipeline, not a restructuring.

---

## S2 -- Scope & Non-Scope

### 2.1 In Scope

1. **New domain record `TradeLegRollupCell`** -- per-position/trade-leg materialized aggregate at DAILY and MONTHLY granularities, containing settled actuals from S5a and forward marks from S4/S6b.
2. **New repository port `TradeLegRollupRepository`** -- CRUD + query interface in `pv-domain/port/repository/`.
3. **New JPA adapter `JpaTradeLegRollupRepository`** -- native SQL adapter in `pv-persistence/adapter/`.
4. **Extension of `RollupMaterializationService`** -- new `materializeTradeLegRollup()` method that computes per-position aggregates alongside the existing portfolio-level rollup.
5. **Refactored `DefaultDashboardQueryService.positionContributions()`** -- reads from `TradeLegRollupRepository` instead of performing on-the-fly aggregation from raw S5a cells and S6b records.
6. **Forward mark inclusion strategy** -- materialize settled data; compute forward data on-the-fly from a bounded set of per-position `ForwardMarkService.computeMonthlyMark()` calls, but only for positions that have forward intervals (determined by rollup metadata, not by scanning S6b).
7. **Guice and Spring wiring** for new repository and service changes.

### 2.2 Defers To

- **Peak/off-peak split.** The existing `RollupCell.isPeak` is always `false` (FR-026 not implemented). Same limitation applies here. When PeakCalendar lands, both rollup tables will need re-materialization.
- **Redis caching of trade-leg rollup reads.** Initial implementation is DB-only. Redis cache with `MarketDataUpdated`/`SettlementComputed` invalidation is a follow-on.
- **Pagination of L3 results.** The current `positionContributions()` returns `List<PositionContribution>` unbounded. This spec does not change the API signature, but S10a identifies the pagination requirement for a follow-on.
- **Production hosting layer concerns.** RLS policies for the new table, Flyway migration, `pg_partman` partitioning -- named but not designed here per D-14.

---

## S3 -- Assumptions & Gaps

| # | Item | Status |
|---|------|--------|
| A-1 | A single `SettlementComputed` event carries `positionId`, and one position maps to one trade-leg. The existing 1:1 relationship between position and trade-leg (D-1: grain = trade-leg x delivery-month) is confirmed by `PositionLedgerEntry` having both `id` (positionId) and `tradeLegId`. | Confirmed in codebase |
| A-2 | The existing `SettlementPublishedConsumer` triggers `materializeForPosition()`. The new trade-leg rollup will be triggered from the same path -- no new Kafka consumer needed. | Design decision |
| A-3 | Forward mark data (D-3: ephemeral, not stored) will NOT be materialized into the trade-leg rollup table. Instead, the rollup stores a `hasForwardIntervals` boolean flag and the `deliveryStatus` derivation. The query-time path calls `ForwardMarkService.computeMonthlyMark()` only for positions where `hasForwardIntervals = true`. | Design decision -- see S4.3 rationale |
| A-4 | The number of positions per portfolio for a given delivery period is bounded by the number of active trades. At 200 trades, the trade-leg rollup query returns at most ~200 rows (one per position per month). This is well within single-query performance budgets. | Assumption |
| G-1 | The existing `RollupMaterializationService` loads settlement cells per-position in a loop (`findByPosition` in a `for` loop, line 80-81). This is an N+1 issue in the existing code. This spec does NOT fix it for the portfolio rollup but uses the bulk `findByPositionIds` for the new trade-leg rollup. | Gap in existing code, noted but not fixed here |

---

## S4 -- Domain Model Additions

### 4.1 `TradeLegRollupCell` (Value Object, Pattern #3)

A new record in `pv-domain/port/repository/` representing a pre-computed per-position aggregate.

**Fields:**

| Field | Type | Description | Source |
|-------|------|-------------|--------|
| `positionId` | `UUID` | S1 position ledger entry ID | S1 |
| `tenantId` | `String` | Tenant identifier (Pattern #32) | S1 |
| `tradeId` | `String` | Trade identifier | S1 |
| `tradeLegId` | `String` | Trade leg identifier | S1 |
| `tradeVersion` | `int` | Trade version | S1 |
| `deliveryPointId` | `String` | Delivery point | S1 |
| `portfolioId` | `String` | Portfolio | S1 |
| `periodStart` | `Instant` | Rollup period start (UTC) | Computed |
| `periodEnd` | `Instant` | Rollup period end (UTC) | Computed |
| `granularity` | `TimeGranularity` | DAILY or MONTHLY | Input |
| `settledMw` | `BigDecimal` | TWA of MW from S5a cells (FR-035) | S5a aggregated |
| `settledMwh` | `BigDecimal` | Sum of MWh from S5a cells (FR-035) | S5a aggregated |
| `avgPrice` | `BigDecimal` | Volume-weighted avg: settledValue / settledMwh | S5a derived |
| `settledValue` | `BigDecimal` | Sum of amount from S5a cells | S5a aggregated |
| `marketValue` | `BigDecimal` | Sum of marketAmount from S5a cells | S5a aggregated |
| `realizedPnl` | `BigDecimal` | Sum of pnl from S5a cells | S5a aggregated |
| `hasForwardIntervals` | `boolean` | Whether S6b records exist beyond settlement horizon | S6b existence check |
| `deliveryStatus` | `String` | SETTLED / PARTIAL / FORWARD -- derived from presence of S5a and S6b data | Derived |
| `quantity` | `BigDecimal` | Signed position quantity from S1 | S1 |
| `volumeUnit` | `String` | Volume unit name | S1 |
| `currency` | `String` | Currency code | S5a |
| `versionHash` | `String` | Content hash for staleness detection | Computed |
| `refreshedAt` | `Instant` | Timestamp of last materialization | System clock |

**Rationale for NOT including forward values in the materialized record:**

1. D-3 explicitly states forward marks are ephemeral. Materializing them into a table creates a staleness problem -- any curve update invalidates all forward rollups for affected positions.
2. The performance bottleneck is settled data (576K+ raw cells), not forward data. Forward `computeMonthlyMark()` is O(1) per position on Redis cache hit (ADR-002). For 200 positions, that is 200 Redis lookups -- acceptable.
3. The `hasForwardIntervals` flag allows the query path to skip `computeMonthlyMark()` entirely for SETTLED positions, reducing the call count to only PARTIAL/FORWARD positions.

### 4.2 No new domain events

The existing `SettlementComputed` event already triggers rollup materialization. The new trade-leg rollup piggybacks on the same event. No new event type is needed.

---

## S5 -- New / Modified Ports

### 5.1 New Port: `TradeLegRollupRepository` (Pattern #18, Repository Port)

Location: `pv-domain/src/main/java/com/power/posval/domain/port/repository/TradeLegRollupRepository.java`

```
interface TradeLegRollupRepository {

    /**
     * Q-10: Trade-leg rollup cells for a portfolio within a delivery range.
     * Returns one row per position per period at the MONTHLY granularity.
     * Ordered by periodStart, tradeLegId.
     * Pattern #18, #32. S7, FR-035.
     */
    List<TradeLegRollupCell> findByPortfolio(
        String tenantId,
        String portfolioId,
        Instant rangeStart,
        Instant rangeEnd,
        TimeGranularity granularity);

    /**
     * Persist or upsert trade-leg rollup cells.
     * Upsert key: (tenant_id, position_id, period_start, granularity).
     * Pattern #18. S7.
     */
    void saveAll(String tenantId, List<TradeLegRollupCell> cells);

    /**
     * Delete stale rollups for a position (e.g., on trade amendment/cancel).
     * Pattern #18. S7.
     */
    void deleteByPositionId(String tenantId, UUID positionId);
}
```

### 5.2 Modified Service: `RollupMaterializationService`

No port change needed -- `RollupMaterializationService` is a concrete domain service (not behind a port interface), already bound in `DomainModule`. It will gain a new method:

```
/**
 * Materialize trade-leg rollup cells for a single position.
 * Triggered alongside portfolio rollup from SettlementPublishedConsumer.
 * Reads S5a cells for the position, S6b existence check for forward intervals,
 * aggregates per FR-035, and persists via TradeLegRollupRepository.
 * S7, Pattern #18, FR-035.
 */
public void materializeTradeLegRollup(
    String tenantId, UUID positionId,
    Instant rangeStart, Instant rangeEnd);
```

### 5.3 Modified Service: `DefaultDashboardQueryService.positionContributions()`

No port signature change. The implementation changes from:

**Before (5 queries + N Redis calls):**
1. `findByPortfolioAndDeliveryRange` -> positions
2. `findByPositionIds` -> 576K+ settlement cells
3. `getForTradeLegIds` -> 576K+ trade interval records
4. Per-position `aggregateSettled()` in Java
5. Per-position `computeMonthlyMark()` -> N Redis/compute calls

**After (1 query + M Redis calls where M << N):**
1. `TradeLegRollupRepository.findByPortfolio()` -> ~200 rows max
2. For positions where `hasForwardIntervals = true`: `computeMonthlyMark()` -> M calls (only PARTIAL/FORWARD positions)

---

## S6 -- Adapters

### 6.1 `JpaTradeLegRollupRepository` (Pattern #18, JPA Adapter)

Location: `pv-persistence/src/main/java/com/power/posval/persistence/adapter/JpaTradeLegRollupRepository.java`

Implements `TradeLegRollupRepository`. Uses `Provider<EntityManager>` injection (same pattern as `JpaRollupRepository`).

**`findByPortfolio` query:**
```sql
SELECT position_id, tenant_id, trade_id, trade_leg_id, trade_version,
       delivery_point_id, portfolio_id,
       period_start, period_end, granularity,
       settled_mw, settled_mwh, avg_price, settled_value, market_value,
       realized_pnl, has_forward_intervals, delivery_status,
       quantity, volume_unit, currency, version_hash, refreshed_at
FROM volume_series.trade_leg_rollup_cell
WHERE tenant_id = :tenantId
  AND portfolio_id = :portfolioId
  AND period_start < :rangeEnd
  AND period_end > :rangeStart
  AND granularity = :granularity
ORDER BY period_start, trade_leg_id
```

Index: `idx_tlr_portfolio_granularity_time` on `(tenant_id, portfolio_id, granularity, period_start)`.

**`saveAll` query:**
Uses `INSERT ... ON CONFLICT (tenant_id, position_id, period_start, granularity) DO UPDATE SET ...` -- same upsert pattern as existing `JpaRollupRepository.saveAll()`.

**`deleteByPositionId` query:**
```sql
DELETE FROM volume_series.trade_leg_rollup_cell
WHERE tenant_id = :tenantId AND position_id = :positionId
```

---

## S7 -- Data Model Impact

### 7.1 New Table: `volume_series.trade_leg_rollup_cell`

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | `UUID` | NOT NULL | PK, `gen_random_uuid()` |
| `tenant_id` | `VARCHAR(64)` | NOT NULL | Multi-tenancy (Pattern #32) |
| `position_id` | `UUID` | NOT NULL | FK to position_ledger_entry.id |
| `trade_id` | `VARCHAR(128)` | NOT NULL | Denormalized from S1 for query convenience |
| `trade_leg_id` | `VARCHAR(128)` | NOT NULL | Denormalized from S1 |
| `trade_version` | `INTEGER` | NOT NULL | Denormalized from S1 |
| `delivery_point_id` | `VARCHAR(128)` | NOT NULL | Denormalized from S1 |
| `portfolio_id` | `VARCHAR(128)` | NOT NULL | Denormalized from S1 |
| `period_start` | `TIMESTAMPTZ` | NOT NULL | Rollup period start |
| `period_end` | `TIMESTAMPTZ` | NOT NULL | Rollup period end |
| `granularity` | `VARCHAR(16)` | NOT NULL | DAILY, MONTHLY |
| `settled_mw` | `NUMERIC(18,8)` | NOT NULL | TWA MW (FR-035) |
| `settled_mwh` | `NUMERIC(18,8)` | NOT NULL | Sum MWh (FR-035) |
| `avg_price` | `NUMERIC(18,8)` | NOT NULL | Volume-weighted avg price |
| `settled_value` | `NUMERIC(18,4)` | NOT NULL | Sum of amount (MONETARY scale) |
| `market_value` | `NUMERIC(18,4)` | NOT NULL | Sum of market amount |
| `realized_pnl` | `NUMERIC(18,4)` | NOT NULL | Sum of PnL |
| `has_forward_intervals` | `BOOLEAN` | NOT NULL | S6b data exists beyond settlement |
| `delivery_status` | `VARCHAR(16)` | NOT NULL | SETTLED/PARTIAL/FORWARD |
| `quantity` | `NUMERIC(18,8)` | NOT NULL | Signed position qty from S1 |
| `volume_unit` | `VARCHAR(32)` | | Volume unit name |
| `currency` | `VARCHAR(3)` | NOT NULL DEFAULT 'EUR' | Currency code |
| `version_hash` | `VARCHAR(64)` | | Content hash for staleness |
| `refreshed_at` | `TIMESTAMPTZ` | NOT NULL DEFAULT NOW() | Last materialization |

### 7.2 Indexes

| Index | Columns | Purpose |
|-------|---------|---------|
| PK | `(id)` | Primary key |
| `uq_tlr_position_period_gran` | `(tenant_id, position_id, period_start, granularity)` UNIQUE | Upsert conflict target |
| `idx_tlr_portfolio_granularity_time` | `(tenant_id, portfolio_id, granularity, period_start)` | L3 query Q-10: `findByPortfolio` |
| `idx_tlr_position` | `(tenant_id, position_id)` | Delete-by-position on amendment/cancel |

### 7.3 RLS Policy (Production Host)

The production hosting layer must add an RLS policy:

```
CREATE POLICY tenant_isolation ON volume_series.trade_leg_rollup_cell
  USING (tenant_id = current_setting('app.tenant_id'));
```

This is a production-host concern, not implemented in the library (D-14).

### 7.4 Partitioning (Production Host)

The table could be partitioned by `tenant_id` (hash) or `period_start` (range). Decision deferred to production host. Estimated row count per tenant: positions x granularities x months. At 200 positions x 2 granularities x 12 months = ~4,800 rows/year/tenant. At 200 tenants = ~960K rows/year total. Single-table with indexes is sufficient at this scale.

### 7.5 Not Bitemporal

This table is a materialized aggregate, not a primary source record. It is re-derivable from S5a + S1. It does NOT have `known_from`/`known_to`/`valid_from`/`valid_to`. When source data changes (trade amendment, revaluation), the rollup is recomputed from scratch. This follows the same pattern as the existing `volume_series.rollup_cell` table.

---

## S8 -- Event Flow

### 8.1 No New Events

The materialization is triggered by the existing `SettlementComputed` event. The flow is:

```
SettlementComputed (Kafka topic: posval.SettlementComputed)
  -> SettlementComputedKafkaListener (pv-app, simulator host)
    -> SettlementPublishedConsumer.process() (pv-kafka)
      -> RollupMaterializationService.materializeForPosition()  // existing portfolio rollup
      -> RollupMaterializationService.materializeTradeLegRollup()  // NEW
```

### 8.2 Modification to `SettlementPublishedConsumer.process()`

The `process()` method currently calls only `materializeForPosition()`. It must also call `materializeTradeLegRollup()` for the same position.

```
@Override
protected void process(SettlementComputed event) {
    if (event.positionId() != null) {
        rollupService.materializeForPosition(
            event.tenantId(), event.positionId(),
            event.intervalStart().toInstant(),
            event.intervalEnd().toInstant());
        rollupService.materializeTradeLegRollup(  // NEW
            event.tenantId(), event.positionId(),
            event.intervalStart().toInstant(),
            event.intervalEnd().toInstant());
    }
}
```

### 8.3 Idempotency

Trade-leg rollup materialization is idempotent by construction: the upsert (`ON CONFLICT ... DO UPDATE`) ensures that re-processing the same `SettlementComputed` event produces identical results. This is consistent with the existing `alreadyProcessed() -> false` pattern in `SettlementPublishedConsumer` (Pattern #26, D-7: re-derive-from-source).

### 8.4 Trade Amendment / Cancellation

When a trade is amended or cancelled:
1. The position is superseded in S1 (bitemporal close via `known_to`).
2. A **new position** is created with a new UUID for the amended version.
3. New settlement cells are computed for the new position.
4. `SettlementComputed` fires for the new position.
5. `materializeTradeLegRollup()` inserts rollup rows for the new position ID.

The old position's rollup rows are **not overwritten** by the upsert — the new position has a different UUID, so the upsert key `(tenant_id, position_id, period_start, granularity)` does not match. The old rows become **orphaned**: they reference a superseded position that will never appear in `findByPortfolioAndDeliveryRange` results (which filters `known_to IS NULL`), but they remain in `trade_leg_rollup_cell` consuming space.

**Orphan cleanup:** Two options:

- **Option A (recommended):** `materializeTradeLegRollup` receives the superseded position ID (available from the amendment event flow) and calls `deleteByPositionId` to remove orphaned rollup rows in the same transaction. This keeps the table clean and avoids unbounded growth.
- **Option B:** Accept orphaned rows. The `findByPortfolio` query would need a join to `position_ledger_entry` to filter by `known_to IS NULL`. This adds query complexity and leaves dead rows in the table.

This spec recommends **Option A** -- explicit orphan cleanup during materialization.

---

## S9 -- Guice Wiring

### 9.1 `PersistenceModule` (pv-guice)

Add binding:

```
bind(TradeLegRollupRepository.class)
    .to(JpaTradeLegRollupRepository.class)
    .in(Singleton.class);
```

### 9.2 `DomainModule` (pv-guice)

No change needed. `RollupMaterializationService` is already bound. Its new `materializeTradeLegRollup()` method will receive `TradeLegRollupRepository` via constructor injection -- the constructor must be updated to accept the new dependency.

### 9.3 `DomainServiceConfig` (pv-app, simulator)

No change needed. `RollupMaterializationService` is already exposed as a Spring bean via `injector.getInstance()`. The new `TradeLegRollupRepository` dependency is resolved within Guice.

### 9.4 `SettlementPublishedConsumer` (pv-kafka)

No wiring change. The consumer already injects `RollupMaterializationService`. The new method is on the same service instance.

---

## S10 -- Cross-Cutting

### 10.1 Tenant Handling (Pattern #32, D-14)

- `TradeLegRollupRepository.findByPortfolio()` requires `tenantId` as first parameter.
- `TradeLegRollupRepository.saveAll()` requires `tenantId` as first parameter.
- `TradeLegRollupRepository.deleteByPositionId()` requires `tenantId` as first parameter.
- All SQL queries include `WHERE tenant_id = :tenantId`.
- No hardcoded tenant IDs in library modules.

### 10.2 Bitemporal Invariants

The trade-leg rollup table is NOT bitemporal (see S7.5). It is a derived materialization from bitemporal source data. The materialization always reads current-knowledge positions (S1 `known_to IS NULL`) and current settlement cells. Re-materialization after source data changes produces correct current state.

### 10.3 Transaction Boundaries

The `materializeTradeLegRollup()` call runs within the same transaction as `materializeForPosition()`, which is already wrapped by `txExecutor.run()` in `SettlementComputedKafkaListener`. Both rollup writes commit atomically.

### 10.4 NumericPrecision (S5.0, D-2)

All aggregation arithmetic in `materializeTradeLegRollup()` uses `NumericPrecision`:
- `VOLUME` domain for MW (scale 8)
- `ENERGY` domain for MWh (scale 8)
- `PRICE` domain for average prices (scale 8)
- `MONETARY` domain for amounts, PnL (scale 4)
- `INTERMEDIATE` domain for accumulation (scale 10)

This replicates the existing patterns in `RollupMaterializationService.aggregate()` and `DefaultDashboardQueryService.aggregateSettled()`.

---

## S10a -- Performance Profile

### Query: `TradeLegRollupRepository.findByPortfolio()` (Q-10)

| Aspect | Value | Rationale |
|--------|-------|-----------|
| **Expected result size** | ~200 rows (one per position per month, MONTHLY granularity) | 200 trades with distinct positions |
| **Response size** | ~20KB for 200 rows | Well under 100KB budget |
| **Index** | `idx_tlr_portfolio_granularity_time` on `(tenant_id, portfolio_id, granularity, period_start)` | Covers the WHERE + ORDER BY |
| **Pagination** | Not required for initial delivery | 200 rows is within single-response budget. If portfolios grow beyond 500 positions, cursor-based pagination by `period_start, trade_leg_id` should be added. |
| **Redis cache** | Deferred | Initial implementation is DB-only. Follow-on: cache key `tlr:{tenantId}:{portfolioId}:{granularity}:{periodStart}:{periodEnd}`, TTL 60s, invalidated on `SettlementComputed` for positions in the portfolio. |

### Materialization Write: `TradeLegRollupRepository.saveAll()`

| Aspect | Value | Rationale |
|--------|-------|-----------|
| **Write size** | 1 row per `SettlementComputed` event (1 position, 1 granularity at a time) | Actual: up to 8 rows (DAILY + MONTHLY x widened range) |
| **Upsert** | `ON CONFLICT DO UPDATE` | No duplicate rows |
| **Impact on settlement latency** | Minimal -- single upsert appended to existing materialization TX | Measured: existing rollup materialization is ~5ms for 4 granularities |

### Comparison: Before vs After for L3 Query

| Metric | Before (on-the-fly) | After (materialized) |
|--------|---------------------|----------------------|
| SQL queries | 3 (positions + cells + intervals) | 1 (trade_leg_rollup_cell) |
| Rows fetched from DB | ~576K (cells) + ~576K (intervals) | ~200 (rollup rows) |
| Java memory | ~100MB for 1.1M records | ~50KB for 200 records |
| ForwardMarkService calls | N (all positions) | M (only PARTIAL/FORWARD) |
| Estimated latency at 200 trades | 5-10 seconds | <50ms DB + M*5ms Redis |

---

## S10b -- Real-Time Push

Not applicable. The L3 `positionContributions()` endpoint is a REST poll endpoint. Real-time push for L3 is not in scope. The existing `DashboardDataChangedEvent` Spring event (fired by `SettlementComputedKafkaListener` after successful processing) already triggers SSE push for L1/L2 dashboard updates in the simulator. L3 will benefit from the same mechanism -- the push event signals "data changed, refetch" and the refetch now hits the materialized rollup table instead of computing on-the-fly.

---

## S10c -- DST Handling

The trade-leg rollup is keyed by UTC period boundaries (`period_start`, `period_end`). Period bucketing uses `truncateToPeriod()` / `advancePeriod()` from `RollupMaterializationService`, which operates in UTC (`ZoneOffset.UTC`).

For DAILY granularity, this means UTC day boundaries (00:00 UTC to 00:00 UTC), not CET/CEST day boundaries. This is consistent with the existing portfolio-level `rollup_cell` table. The L4 `dailyAggregates()` method handles CET/CEST day boundary alignment at query time.

DST transition days at MONTHLY granularity: no special handling needed because months are calendar-aligned in UTC. The settlement cells within each month already have correct interval counts (92 on spring-forward day, 100 on fall-back day), and the TWA aggregation weights by actual interval duration, so DST transitions are handled correctly by the existing aggregation math.

---

## S11 -- Regulatory Impact

**REMIT / EMIR / MiFID II:** None. The trade-leg rollup is a read-side materialization for dashboard performance. It does not affect transaction reporting timeliness, does not introduce new reportable data fields, and does not change the settlement calculation logic. All regulatory reporting reads from S5a settlement cells directly, not from rollups.

---

## S12 -- Testing Strategy

### 12.1 Unit Tests (`pv-domain`, `*Test.java`)

**`TradeLegRollupMaterializationTest`** -- tests the new `materializeTradeLegRollup()` method in `RollupMaterializationService`.

Test cases:
1. **Single position, two cells** -- verifies TWA for MW, sum for MWh, volume-weighted avg price, sum for amounts/PnL.
2. **Position with no cells** -- verifies zero settled aggregates, `deliveryStatus = FORWARD`, `hasForwardIntervals = true` (mocked S6b returns data).
3. **Position with cells and forward intervals** -- verifies `deliveryStatus = PARTIAL`, `hasForwardIntervals = true`.
4. **Position with cells only** -- verifies `deliveryStatus = SETTLED`, `hasForwardIntervals = false`.
5. **Superseded position** -- verifies `deleteByPositionId` is called.
6. **Idempotency** -- two invocations with same data produce identical output.

Test approach: hand-mocked `SettlementCellRepository`, `PositionLedgerRepository`, `TradeLegRollupRepository`, `TradeIntervalCache` (same pattern as existing `RollupMaterializationServiceTest`).

**`PositionContributionsFromRollupTest`** -- tests the refactored `positionContributions()` in `DefaultDashboardQueryService`.

Test cases:
1. **Rollup available** -- verifies `TradeLegRollupRepository.findByPortfolio()` is called and result is mapped to `PositionContribution`.
2. **Forward top-up** -- verifies `ForwardMarkService.computeMonthlyMark()` is called only for rows where `hasForwardIntervals = true`.
3. **Empty rollup** -- verifies empty list returned.
4. **Mixed SETTLED/PARTIAL/FORWARD** -- verifies correct `deliveryStatus` pass-through.

### 12.2 Integration Tests (`pv-integration-tests`, `*IT.java`)

**`TradeLegRollupIT`** -- Testcontainers PG16. Exercises:
1. DDL: table creation, indexes, unique constraint.
2. `saveAll` upsert: insert then update with changed values.
3. `findByPortfolio`: filtered by tenant, portfolio, granularity, time range.
4. `deleteByPositionId`: removes only the target position's rows, preserves others.
5. Tenant isolation: two tenants, verify no cross-tenant leakage.

### 12.3 Contract Tests

**Port-Adapter contract for `TradeLegRollupRepository`:** `JpaTradeLegRollupRepository` is tested against the port interface contract using Testcontainers PG16 (S18.3 pattern). Not H2.

---

## S13 -- Constraint Compatibility

| Constraint | Status | Rationale |
|------------|--------|-----------|
| D-1 | Compatible | Rollup grain aligns with S1 grain: one rollup row per position (= trade-leg x delivery-month) per period. No interval fan-out in the rollup. |
| D-2 | Not applicable | Price expressions are not involved in rollup materialization; settled prices come from S5a cells. Forward prices are computed on-the-fly via `ForwardMarkService` / `PriceEvaluator`. |
| D-3 | Compatible | Forward marks are NOT materialized into the rollup table. They remain ephemeral, computed at query time. The `hasForwardIntervals` flag is derived from S6b existence, not from stored forward values. |
| D-11 | Not applicable | Unified volume is upstream (S3/S6b). The rollup reads resolved values, does not interact with `VolumeReference`. |
| D-12 | Compatible | S6b trade interval cache is read (existence check only for `hasForwardIntervals`), not modified. |
| D-13 | Compatible | All new code is in `pv-domain` (record, port interface), `pv-persistence` (JPA adapter), `pv-kafka` (consumer modification), and `pv-guice` (binding). No Spring types in any of these modules. |
| D-14 | Compatible | No hardcoded tenant IDs, no `hbm2ddl`, no in-memory cache adapters. All SQL includes `tenant_id` filter. RLS policy is named as a production-host concern. |

---

## S14 -- Open Items

| # | Question | Who Decides | Impact if Unresolved |
|---|----------|-------------|----------------------|
| OI-1 | Should `materializeTradeLegRollup()` also materialize at DAILY granularity, or only MONTHLY? MONTHLY is sufficient for L3 `positionContributions()`. DAILY would benefit a potential future "per-trade daily PnL" view. | Product / solutions-architect | If MONTHLY only: simpler, fewer rows. If DAILY: ~30x more rows per position but enables future L4 trade-level daily drill-down. Recommend: both, matching the existing `PORTFOLIO_GRANULARITIES` list for consistency. |
| OI-2 | Should the `findByPortfolio` query join to `position_ledger_entry` to filter `known_to IS NULL`, or should stale rollup cleanup (Option A from S8.4) be the sole correctness mechanism? | solutions-architect | Option A (cleanup on supersession) is recommended. Join adds query complexity and the cleanup is triggered atomically in the same event flow. |
| OI-3 | Should the `positionContributions()` method keep a fallback to on-the-fly computation if the rollup table is empty (e.g., during initial rollout before backfill)? | solutions-architect | Recommend: yes, with a log warning. Fallback uses the existing code path. Once all positions have been materialized, the fallback path is never hit. |
| OI-4 | Backfill strategy: how do we populate `trade_leg_rollup_cell` for existing positions that already have settlement cells but were never materialized? | implementation-engineer | Options: (a) one-time batch job that calls `materializeTradeLegRollup` for all active positions, (b) lazy materialization on first L3 query. Recommend (a) as a one-time migration step. |

---

## Appendix A -- Materialization Algorithm Pseudocode

```
materializeTradeLegRollup(tenantId, positionId, rangeStart, rangeEnd):

  1. position = ledgerRepo.findById(positionId)
     if position is empty -> return
     if position.knownTo != null -> // superseded
       tradeLegRollupRepo.deleteByPositionId(tenantId, positionId)
       return

  2. for each granularity in [DAILY, MONTHLY]:
       wideStart = truncateToPeriod(rangeStart, widening(granularity))
       wideEnd   = advancePeriod(truncateToPeriod(rangeEnd, widening(granularity)), widening(granularity))

       // Load S5a cells for this position in the widened range
       cells = cellRepo.findByPosition(tenantId, positionId, wideStart, wideEnd)

       // Check S6b forward interval existence (lightweight existence query)
       hasForward = tradeIntervalCache.getForTradeLeg(tenantId, position.tradeLegId, wideStart, wideEnd).isNotEmpty()

       // Group cells by period bucket
       for each period bucket in [wideStart, wideEnd):
         bucketCells = cells filtered to this bucket
         settled = aggregateSettled(bucketCells)  // reuse existing FR-035 logic

         deliveryStatus = derive(bucketCells.nonEmpty, hasForward)

         rollupCell = TradeLegRollupCell(
           positionId, tenantId, position.tradeId, position.tradeLegId,
           position.tradeVersion, position.deliveryPointId, position.portfolioId,
           bucketStart, bucketEnd, granularity,
           settled.netMw, settled.netMwh, settled.avgPrice,
           settled.settledValue, settled.marketValue, settled.pnl,
           hasForward, deliveryStatus,
           position.quantity, position.volumeUnit, currency, versionHash, now())

         cells.add(rollupCell)

       tradeLegRollupRepo.saveAll(tenantId, cells)
```

## Appendix B -- Refactored `positionContributions()` Pseudocode

```
positionContributions(tenantId, portfolioId, periodStart, periodEnd):

  1. rollups = tradeLegRollupRepo.findByPortfolio(
       tenantId, portfolioId, periodStart, periodEnd, MONTHLY)

     if rollups.isEmpty():
       // Fallback to on-the-fly (OI-3, transitional)
       return positionContributionsOnTheFly(tenantId, portfolioId, periodStart, periodEnd)

  2. result = []
     for each rollup in rollups:
       forwardMw = ZERO
       forwardMwh = ZERO
       forwardMarkValue = ZERO
       unrealizedMtm = ZERO

       if rollup.hasForwardIntervals:
         monthlyMark = forwardMarkService.computeMonthlyMark(
           tenantId, rollup.positionId, periodStart, periodEnd)
         if monthlyMark != null:
           forwardMw = derive TWA from monthlyMark
           forwardMwh = monthlyMark.totalMwh
           forwardMarkValue = monthlyMark.forwardMtm
           unrealizedMtm = monthlyMark.forwardMtm

       result.add(PositionContribution(
         rollup.positionId, rollup.tradeId, rollup.tradeLegId,
         rollup.tradeVersion, rollup.periodStart, rollup.periodEnd,
         rollup.quantity, rollup.volumeUnit, rollup.deliveryPointId,
         rollup.deliveryStatus,
         rollup.settledMw, rollup.settledMwh, rollup.avgPrice,
         rollup.settledValue, rollup.marketValue, rollup.realizedPnl,
         forwardMw, forwardMwh, forwardMarkValue, unrealizedMtm,
         rollup.currency))

  3. return result
```

---

## S15 -- Multi-Select Trade Legs & Contiguous Day Range (Server-Side)

### 15.1 Overview

Two UX-driven server-side enhancements to the L3/L4 dashboard queries:

1. **Multi-select trade legs (netted):** Users select multiple positions in the L3 grid. L4 daily aggregates and sub-daily grids show the **netted aggregate** across the selected subset (FR-035: TWA for MW, sum for MWh, volume-weighted avg for price).

2. **Contiguous day range:** Users click a start day, Shift+click an end day in the L4 MonthViewGrid. The sub-daily grid loads the full contiguous range (e.g., Mon 00:00 → Fri 00:00) in a single API call.

### 15.2 API Changes

#### 15.2.1 `DashboardQueryService` Port — Overloaded Methods

The existing single-`positionId` signatures remain for backward compatibility. New overloads accept `List<UUID>`:

```java
// L4 Month View — multi-position daily aggregates
List<DailyAggregate> dailyAggregates(String tenantId,
                                      String portfolioId,
                                      List<UUID> positionIds,  // NEW: subset selection
                                      Instant monthStart,
                                      Instant monthEnd,
                                      String timezone);

// L4 Settled Day View — multi-position, contiguous day range
List<SettlementCell> settledDayDetail(String tenantId,
                                       String portfolioId,
                                       List<UUID> positionIds,  // NEW
                                       Instant dayStart,        // range start
                                       Instant dayEnd,          // range end (multi-day)
                                       TimeGranularity subDailyGranularity);

// L4 Forward Day View — multi-position, contiguous day range
List<ForwardIntervalDetail> forwardDayDetail(String tenantId,
                                              String portfolioId,
                                              List<UUID> positionIds,  // NEW
                                              Instant dayStart,        // range start
                                              Instant dayEnd,          // range end (multi-day)
                                              TimeGranularity subDailyGranularity);
```

**Semantics:**
- `positionIds` empty or null → portfolio-scoped (all positions), same as existing `positionId=null` behavior
- `positionIds` with 1 entry → equivalent to the existing single-position path
- `positionIds` with N entries → netted aggregate across the subset

#### 15.2.2 REST Controller Changes (`DashboardController`)

The existing single-`positionId` query parameter becomes a repeatable parameter:

```
GET /api/dashboard/portfolios/{portfolioId}/daily
    ?tenantId=default
    &monthStart=2026-09-01T00:00:00Z
    &monthEnd=2026-10-01T00:00:00Z
    &positionId=uuid-1&positionId=uuid-2&positionId=uuid-3   // repeatable
    &timezone=Europe/Berlin

GET /api/dashboard/settlements/day
    ?tenantId=default&portfolioId=default
    &dayStart=2026-09-01T00:00:00Z
    &dayEnd=2026-09-06T00:00:00Z    // contiguous range: 5 days
    &positionId=uuid-1&positionId=uuid-2
    &granularity=MIN_15

GET /api/dashboard/forward/day
    ?tenantId=default&portfolioId=default
    &dayStart=2026-09-15T00:00:00Z
    &dayEnd=2026-09-20T00:00:00Z    // contiguous range: 5 days
    &positionId=uuid-1&positionId=uuid-2
    &granularity=MIN_15
```

Spring MVC automatically binds repeated `positionId` params to `List<String>` when the controller parameter is declared as `@RequestParam(required = false) List<String> positionId`.

**Backward compatibility:** A single `positionId=uuid` still works (list of one). No `positionId` param → portfolio-scoped. Existing clients are unaffected.

#### 15.2.3 Contiguous Day Range — Response Considerations

When `dayStart` to `dayEnd` spans multiple days (e.g., 5 days = 480 intervals at MIN_15):

- Intervals are returned in `intervalStart` order across the full range
- Each interval carries its `intervalStart`/`intervalEnd` timestamps, so the UI can derive day boundaries for visual separators
- Response size: 480 rows × ~200 bytes ≈ ~96KB — well within budget
- At HOURLY aggregation: 5 days × 24 hours = 120 rows — trivial

**Upper bound:** The API should reject ranges exceeding 31 days to prevent unbounded queries. Return HTTP 400 with message `"Day range must not exceed 31 days"`.

### 15.3 Implementation in `DefaultDashboardQueryService`

#### 15.3.1 `dailyAggregates` Multi-Position

The existing `dailyAggregates(... UUID positionId ...)` method already handles:
- `positionId != null` → single-position path
- `positionId == null` → portfolio-scoped path

The new overload filters positions to the selected subset:

```
dailyAggregates(tenantId, portfolioId, positionIds, monthStart, monthEnd, timezone):
  if positionIds is empty:
    delegate to existing portfolio-scoped path
  else:
    // Same logic as portfolio-scoped, but filter positions to subset
    positions = ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, monthStart, monthEnd)
    positions = positions.filter(p -> positionIds.contains(p.id()))
    // ... rest of daily aggregate logic with filtered positions
```

#### 15.3.2 `settledDayDetail` / `forwardDayDetail` Multi-Position + Multi-Day

The existing methods already support portfolio-scoped queries (positionId=null fetches all positions, then bulk-queries S5a/S6b). The multi-position variant is a filtered subset:

```
settledDayDetail(tenantId, portfolioId, positionIds, dayStart, dayEnd, granularity):
  if positionIds is empty:
    positions = ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd)
  else:
    positions = positionIds.map(id -> ledgerRepo.findById(id)).filter(present)
  ids = positions.map(id)
  cells = cellRepo.findByPositionIds(tenantId, ids, dayStart, dayEnd)
  // aggregate if needed — same FR-035 logic, works across multi-day range
```

The contiguous day range requires no special handling — `dayStart`/`dayEnd` already define an arbitrary UTC range. The existing overlap query (`intervalStart < dayEnd AND intervalEnd > dayStart`) naturally spans multiple days.

### 15.4 Performance Considerations

| Scenario | Intervals | Rows | Acceptable? |
|----------|-----------|------|-------------|
| 1 position, 1 day | 96 | 96 | Yes |
| 5 positions (netted), 1 day | 96 | 96 (netted) | Yes |
| 1 position, 5 days | 480 | 480 | Yes |
| 5 positions (netted), 5 days | 480 | 480 (netted) | Yes |
| 10 positions, 31 days (max) | 2976 | 2976 | Borderline — acceptable for power grids |

Netted view returns the same number of interval rows regardless of position count (aggregation collapses positions). The position count affects only the server-side query size, not the response size.

### 15.5 Constraint Compatibility

- **D-13:** All changes are in `pv-domain` (port interface overloads, service implementation) and `pv-app` (controller). No Spring types in library modules.
- **D-14:** No hardcoded tenants. `tenantId` flows through all calls.
- **FR-035:** Aggregation rules (TWA, sum, vol-weighted avg) are the same for single and multi-position — the existing `aggregateSettled()` and `aggregateForwardVolume()` methods work on any list of cells, regardless of how many positions contributed them.

---

**Handoff:** This specification is ready for review. Upon approval, hand off to:

**implementation-engineer** (server-side):
1. `TradeLegRollupCell` record in `pv-domain/port/repository/`
2. `TradeLegRollupRepository` interface in `pv-domain/port/repository/`
3. `JpaTradeLegRollupRepository` in `pv-persistence/adapter/`
4. `materializeTradeLegRollup()` method in `RollupMaterializationService`
5. Modified `SettlementPublishedConsumer.process()`
6. Refactored `DefaultDashboardQueryService.positionContributions()`
7. Guice binding in `PersistenceModule`
8. Constructor update for `RollupMaterializationService` (new `TradeLegRollupRepository` + `TradeIntervalCache` dependencies)
9. Multi-position overloads for `dailyAggregates`, `settledDayDetail`, `forwardDayDetail` (S15)
10. `DashboardController` parameter changes for repeatable `positionId` and multi-day range validation (S15)
11. Unit tests + integration tests per S12

**ui-solution-architect** (client-side):
12. Component-level spec for multi-select trade legs in L3 PositionContributions grid
13. Component-level spec for contiguous day range selection in L4 MonthViewGrid
14. Selection state management (Zustand store or React state)
15. Keyboard accessibility for multi-select (Ctrl+click, Shift+click, Shift+Arrow)
16. Grid changes for netted aggregation display and day-boundary separators
