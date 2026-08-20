# Technical Specification -- Buy/Sell Trade Direction v1.0

## S1 -- Metadata & Status

| Field | Value |
|-------|-------|
| Author | solutions-architect |
| Status | DRAFT |
| Version | 1.0 |
| Date | 2026-08-20 |
| Depends On | `buy-sell-trade-direction.functional.md`, ADR-001 (Library + Guice), functional-spec-position-valuation-v1.0 (FR-034, FR-030, FR-035, FR-056, FR-071, FR-075, FR-077) |
| Layer | **Library-scope** (pv-domain, pv-persistence) + **Simulator-scope** (pv-app DTOs, controllers, UI) |
| Subsystems Touched | **S1** (Position Ledger), **S5a** (Settlement Cells), **S5b** (Forward Marks), **S5c** (EOD Struck Marks), **S7** (Rollups) |

**Cross-cutting note:** Five subsystems are touched. This is justified because direction is a fundamental trade attribute that flows through the entire valuation pipeline. The change is narrow (one new field, one arithmetic fix) but wide (many touch points). Each touch point is small and localised.

---

## S2 -- Scope & Non-Scope

### 2.1 In Scope

1. New `TradeDirection` enum in `pv-domain/model/` (BUY, SELL).
2. Add `direction` field to `TradeCapture` command, `PositionLedgerEntry` domain model, `PositionLedgerEntryEntity` JPA entity, persistence mapper.
3. Correct PnL/MtM calculation in `SettlementMaterializationJob.buildResult()`, `SettlementRevaluationService.buildSettlementCell()`, `ForwardMarkJob.buildResult()`, `EodStrikeJob.buildResult()` by signing energy using `signum(position.quantity())`.
4. Propagate `direction` through `TradeLegRollupCell`, `PositionContribution`, `PositionContributionDto`.
5. Add `direction` column to `trade_leg_rollup_cell` table.
6. Update `TradeCaptureRequest` DTO and `toCommand()` in pv-app.
7. Update `PositionLedger.tsx` UI to show Direction column.
8. Update `positionContributionSchema` in `api.ts`.

### 2.2 Defers To

1. **Data migration backfill** -- the Flyway migration that backfills `direction` on existing rows is specified here but implementation is deferred to the production hosting layer (which owns Flyway). The simulator uses `hbm2ddl.auto=update` and does not need a migration.
2. **TradeAmendRequest direction field** -- the amend flow already re-runs trade capture with a new version. Adding `direction` to the amend DTO is a straightforward extension of the same pattern. Deferred to a follow-up ticket.
3. **REMIT/EMIR/MiFID II report generation** -- this feature populates the data; report submission is out of scope.
4. **Real-time push** -- no new SSE/WebSocket endpoint is introduced. Existing SSE push (if any) for settlement events already carries `positionId`; the client re-fetches via REST and gets the updated direction. No push-path change needed.

---

## S3 -- Assumptions & Gaps

| # | Assumption / Gap | Status |
|---|---|---|
| A-1 | `VolumeRecord.energy()` and `VolumeRecord.volume()` are always non-negative (physical quantities). This is confirmed by inspecting `VolumeResolver` implementations and the `VolumeUnit.toEnergy()` method which does not negate. | **Confirmed** by code inspection |
| A-2 | The `quantity` field on `TradeCapture` is currently passed through unsigned by the caller, and the handler stores it as-is. After this change, the handler takes `abs(quantity)` and applies direction sign. | **Design decision** |
| A-3 | No downstream consumer of `SettlementCell` assumes positive `volumeMwh`. The rollup aggregation uses `BigDecimal::add` which handles signed values. The `NumericCell` UI component renders signed values with `showSign`. | **Confirmed** by code inspection of `RollupMaterializationService`, `DefaultDashboardQueryService`, `PositionLedger.tsx` |
| A-4 | `SettlementCell` is not a bitemporal entity -- it is delete-then-insert on revaluation. Adding signed volume/amount does not violate bitemporal invariants. | **Confirmed** by S5a spec and code |
| G-1 | The exact column type for `direction` in the production Flyway migration (VARCHAR(4)) needs confirmation from the DBA. The simulator's `hbm2ddl` will auto-create it. | **Gap** -- deferred to production hosting layer |

---

## S4 -- Domain Model Additions

### 4.1 New: `TradeDirection` enum

**Location:** `pv-domain/src/main/java/com/power/posval/domain/model/TradeDirection.java`

**Pattern:** #4 (Domain Enum), same pattern as `VolumeUnit`, `QualityState`, `TimeGranularity`.

```
public enum TradeDirection {
    BUY,   // Long position: quantity positive, profit when market > trade
    SELL;  // Short position: quantity negative, profit when market < trade

    public int sign() {
        return this == BUY ? 1 : -1;
    }
}
```

The `sign()` method encapsulates the direction-to-sign mapping in one place. All call sites use `direction.sign()` rather than inline ternaries.

### 4.2 Modified: `TradeCapture` command record

**Location:** `pv-domain/src/main/java/com/power/posval/domain/command/TradeCapture.java`

**Change:** Add `TradeDirection direction` as a mandatory field. Position it after `quantity` for readability. The record gains one new component.

**Pattern:** #17 (Command). FR-001.

### 4.3 Modified: `PositionLedgerEntry` domain model

**Location:** `pv-domain/src/main/java/com/power/posval/domain/model/PositionLedgerEntry.java`

**Change:** Add `TradeDirection direction` field. Add `direction(TradeDirection v)` to the Builder. Add `requireNonNull(direction, "direction")` to `build()`.

**Pattern:** #1 (Immutable Domain Entity), #6 (Builder), #35 (Bitemporal Audit). FR-034, D-1.

**Rationale:** Direction is persisted as a denormalized display/audit attribute per the functional spec section 1.2. Computation uses `signum(quantity)`, not this field.

### 4.4 Modified: `PositionContribution` value object

**Location:** `pv-domain/src/main/java/com/power/posval/domain/port/service/dashboard/PositionContribution.java`

**Change:** Add `String direction` field (after `volumeUnit`). Value is `"BUY"` or `"SELL"`.

**Pattern:** #3 (Value Object). D-1, FR-035.

### 4.5 Modified: `TradeLegRollupCell` value object

**Location:** `pv-domain/src/main/java/com/power/posval/domain/port/repository/TradeLegRollupCell.java`

**Change:** Add `String direction` field (after `volumeUnit`). Value is `"BUY"` or `"SELL"`.

**Pattern:** #3 (Value Object). S7, FR-035.

---

## S5 -- New / Modified Ports

### 5.1 No new port interfaces required

All changes are to existing domain models, commands, and service implementations. No new repository methods, no new event publisher methods, no new cache methods. The existing ports are sufficient.

**Reuse check (mandatory):**
- `PositionLedgerRepository` -- existing `save()`, `supersede()`, `findById()` methods are unchanged. The entity gains a column but the port interface is model-agnostic (it accepts/returns `PositionLedgerEntry` which now includes `direction`).
- `SettlementCellRepository` -- unchanged. `SettlementCell` record is unchanged (direction is encoded in the sign of `volumeMwh`, `amount`, etc.).
- `TradeLegRollupRepository` -- existing `saveAll()` and `findByPortfolio()` pass through `TradeLegRollupCell` which gains the `direction` field. The native SQL in the adapter must be updated (see S6).
- `DashboardQueryService.positionContributions()` -- return type `PositionContribution` gains `direction`. No interface signature change.

---

## S6 -- Adapters

### 6.1 `JpaPositionLedgerRepository` (pv-persistence)

**Pattern:** #18 (Repository Adapter). S1.

**Changes:**

1. `toEntity(PositionLedgerEntry d)` -- map `d.direction().name()` to `e.setDirection(...)`.
2. `toDomain(PositionLedgerEntryEntity e)` -- map `e.getDirection()` to `TradeDirection.valueOf(e.getDirection())`. For backward compatibility during migration, if `e.getDirection()` is null, infer from quantity sign: `e.getQuantity().signum() >= 0 ? TradeDirection.BUY : TradeDirection.SELL`.

### 6.2 `PositionLedgerEntryEntity` (pv-persistence)

**Pattern:** #35 (Bitemporal Entity). S1.

**Change:** Add column `direction VARCHAR(4) NOT NULL` (with a default of `'BUY'` for the `hbm2ddl` path; the Flyway migration uses `CASE WHEN quantity >= 0 THEN 'BUY' ELSE 'SELL' END`).

```
@Column(name = "direction", nullable = false, length = 4)
private String direction;
```

Plus getter/setter.

### 6.3 `JpaTradeLegRollupRepository` (pv-persistence)

**Pattern:** #18 (Repository Adapter). S7.

**Changes:**

1. `findByPortfolio()` SELECT list -- add `direction` column. Column index shifts for all subsequent columns in `mapToRollupCell()`.
2. `saveAll()` INSERT/UPSERT -- add `direction` to the column list and `VALUES` clause; add `direction = EXCLUDED.direction` to the `ON CONFLICT DO UPDATE SET` clause.
3. `mapToRollupCell()` -- add `direction` extraction at the appropriate column index.

### 6.4 No changes to Redis or Kafka adapters

`SettlementCell` record is unchanged. Forward marks (`ForwardMarkStore`) store `markValue` which will now be signed -- no schema change needed (it is a `BigDecimal`). Kafka event payloads (`PositionEntryCaptured`, `SettlementComputed`) are unchanged -- they carry `positionId` and the consumer loads the full position from the repository.

---

## S7 -- Data Model Impact

### 7.1 Table: `position.position_ledger_entry`

| Change | Column | Type | Nullable | Default |
|--------|--------|------|----------|---------|
| ADD | `direction` | `VARCHAR(4)` | NOT NULL | Backfill: `CASE WHEN quantity >= 0 THEN 'BUY' ELSE 'SELL' END` |

**Flyway migration outline** (production hosting layer responsibility):

```
-- V<next>__add_direction_to_position_ledger_entry.sql
ALTER TABLE position.position_ledger_entry
    ADD COLUMN direction VARCHAR(4);

UPDATE position.position_ledger_entry
    SET direction = CASE WHEN quantity >= 0 THEN 'BUY' ELSE 'SELL' END;

ALTER TABLE position.position_ledger_entry
    ALTER COLUMN direction SET NOT NULL;
```

**No new indexes required.** Direction is not a query filter -- positions are looked up by `(tenant_id, trade_id, trade_leg_id)` or `(tenant_id, portfolio_id, delivery_start, delivery_end)`. Direction is a display/audit attribute.

### 7.2 Table: `volume_series.trade_leg_rollup_cell`

| Change | Column | Type | Nullable | Default |
|--------|--------|------|----------|---------|
| ADD | `direction` | `VARCHAR(4)` | NULL initially | Backfill from S1 position direction |

**Why nullable initially:** Rollup cells are derived/rebuildable (S7.5). The production migration can add the column as nullable, then a backfill job re-materializes all rollups (which is idempotent). Alternatively, set default `'BUY'` and let the next settlement event overwrite.

### 7.3 No schema change to `settlement_cell`

Direction is encoded in the sign of `volumeMwh`, `volumeMw`, `amount`, `marketAmount`, `pnl`. These columns are already `NUMERIC(15,8)` / `NUMERIC(15,4)` which support negative values. No column addition needed.

---

## S8 -- Event Flow

### 8.1 No new events, no new topics

The existing event flow is unchanged:

1. `POST /api/trades/capture` (with new `direction` field in JSON body)
2. `DefaultTradeCaptureHandler.handle()` -- creates `PositionLedgerEntry` with `direction` and signed `quantity`
3. Outbox write: `PositionEntryCaptured` (unchanged payload: `tenantId`, `positionId`, `timestamp`)
4. `OutboxRelayProducer` polls outbox, produces to `posval.PositionEntryCaptured`
5. `TradeCapturedConsumer` loads position from repo (which now includes `direction`), calls `SettlementMaterializationJob.execute()`
6. `buildResult()` uses `signum(position.quantity())` to sign energy -- produces correctly signed settlement cells
7. Outbox write: `SettlementComputed` (unchanged)
8. `SettlementPublishedConsumer` triggers rollup materialization (which now writes `direction` to `TradeLegRollupCell`)

**Idempotency:** Unchanged. The `alreadyProcessed()` check on consumers is unaffected. Re-processing produces identical signed values because `signum(quantity)` is deterministic.

### 8.2 Outbox pattern compliance

All domain state changes continue to write to outbox in the same `UnitOfWork.execute()` (Pattern #24, D-9, TR-038). No direct-produce-from-domain path is introduced.

---

## S9 -- Guice Wiring

### 9.1 No new bindings required

`TradeDirection` is a simple enum with no DI dependencies. All modified services (`DefaultTradeCaptureHandler`, `SettlementMaterializationJob`, `SettlementRevaluationService`, `ForwardMarkJob`, `EodStrikeJob`, `DefaultDashboardQueryService`, `RollupMaterializationService`) are already bound in `DomainModule` / `HandlersModule` / `QueryServicesModule`. Their constructor signatures are unchanged.

### 9.2 pv-app Spring wiring

The `TradeCaptureRequest` DTO gains a `direction` field. The existing `toCommand()` method is updated to parse `TradeDirection.valueOf(direction)`. No new `@Bean` methods needed. Existing `injector.getInstance(TradeCaptureHandler.class)` in the Spring config continues to work.

---

## S10 -- Cross-Cutting

### 10.1 Tenant handling

**No change.** Direction is a per-trade attribute. All existing tenant-filtered queries remain unchanged. `TradeDirection` does not participate in any tenant-scoped query predicate. Every port method that touches `PositionLedgerEntry` already accepts `tenantId`.

### 10.2 Bitemporal invariants

**No mutation of bitemporal entities.** The `direction` field is set at creation time and immutable thereafter. Trade amendments create new entries via `supersede()` (close old `known_to`, insert new). The new entry carries the (possibly changed) direction. This is fully consistent with the append-only bitemporal model.

### 10.3 Transaction boundaries

**Unchanged.** All writes occur within existing `UnitOfWork.execute()` boundaries. The additional `direction` field is written in the same transaction as the rest of the `PositionLedgerEntry`.

### 10.4 Cache invalidation

**No new cache keys.** Direction does not affect cache key structure. The existing `TradeIntervalCache` rebuild on supersession (in `DefaultTradeCaptureHandler`) continues to work unchanged. Forward mark cache (`ForwardMarkStore`) stores `markValue` which will now be signed -- the cache key (tenantId, positionId, intervalStart, intervalEnd) is unchanged, and the value is overwritten on recomputation.

---

## S10a -- Performance Profile

### 10a.1 No new query paths

This feature modifies existing query results (adding one column) but does not introduce new query paths, new endpoints, or new aggregation patterns.

### 10a.2 Settlement cell computation

The arithmetic change (`energy * signum(quantity)`) adds one `BigDecimal.signum()` call and one `BigDecimal.multiply()` per interval. For a 5-year trade (87,600 quarter-hour intervals), this adds ~87,600 `signum()` + `multiply()` operations, each nanosecond-scale. **Negligible performance impact.**

### 10a.3 Pagination

**Unchanged.** No new paginated endpoints.

### 10a.4 Response size

**Marginal increase.** `direction` adds 3-4 characters (`"BUY"` or `"SELL"`) per `PositionContribution` row. For a typical L3 response of 200 rows, this is ~800 bytes. Well within the <100KB budget.

### 10a.5 Connection pooling

**No change.** No new DataSource or connection paths.

---

## S10b -- Real-Time Push

**Not applicable.** No new push endpoints or push event types. Existing SSE push for `SettlementComputed` (if wired) carries `positionId`; the client re-fetches the updated position data which now includes correct signed PnL.

---

## S10c -- DST Handling

**Not applicable to this feature.** As documented in functional spec section 6, direction does not alter interval generation, gate closure, or time-series mechanics. The signed-energy multiplication produces correct results on both 23-hour (92 interval) and 25-hour (100 interval) days because signing is per-interval.

---

## S11 -- Regulatory Impact

### 11.1 REMIT (Regulation 1227/2011)

**Positive impact.** ACER TRUM Table 1 Field 16 requires a Buy/Sell indicator. The `direction` field on `PositionLedgerEntry` provides this. Any future REMIT reporting adapter can read `direction` directly from the position ledger.

### 11.2 EMIR (Regulation 648/2012)

**Positive impact.** ESMA RTS Field 29 (Direction) is satisfied by `TradeDirection`.

### 11.3 MiFID II RTS 22

**Positive impact.** Field 28 (Buy/sell indicator) maps directly to `TradeDirection.BUY` -> `BUYI`, `TradeDirection.SELL` -> `SELL`.

### 11.4 Summary

This feature **closes a regulatory data gap**. No new reporting obligations are introduced; the data is now available for downstream report generation.

---

## S12 -- Testing Strategy

### 12.1 Unit Tests (pv-domain)

**New test:** `TradeDirectionTest` -- verify `sign()` returns 1 for BUY, -1 for SELL. Trivial but ensures the contract.

**Modified test:** `DefaultTradeCaptureHandlerTest`
- Add test: BUY direction with positive quantity -> positive quantity on entry, direction = BUY.
- Add test: SELL direction with positive quantity -> negative quantity on entry, direction = SELL.
- Add test: SELL direction with negative quantity (pre-signed) -> negative quantity (abs then negate), direction = SELL. Verifies no double-negation.
- Add test: null direction -> exception (NullPointerException or IllegalArgumentException).
- Existing idempotency tests pass unchanged (re-capture returns existing entries with direction).

**Modified test:** `AbstractMaterializationJobTest` / settlement test fixtures
- Add test case: SELL position -> settlement cell has negative `volumeMwh`, negative `amount`, negative `marketAmount`, correctly signed `pnl`.
- Add test case: BUY position with market price > trade price -> positive pnl.
- Add test case: SELL position with market price > trade price -> negative pnl.
- Add test case: SELL position with market price < trade price -> positive pnl.

**Modified test:** `SettlementRevaluationServiceTest`
- Mirror the settlement cell sign tests for the revaluation path.

**Modified test:** `RollupMaterializationServiceTest` / `TradeLegRollupMaterializationTest`
- Verify `direction` is propagated to `TradeLegRollupCell`.
- Verify aggregation of signed values (BUY + SELL positions in same portfolio net correctly).

**Modified test:** `PositionContributionsFromRollupTest` / `MultiPositionDashboardQueryServiceTest`
- Verify `direction` is propagated to `PositionContribution`.
- Verify net portfolio summary sums signed PnL correctly across BUY and SELL positions.

### 12.2 Integration Tests (pv-integration-tests)

**Testcontainers PostgreSQL 16** -- not H2.

- End-to-end: capture BUY trade -> verify settlement cells -> verify rollup -> verify L3 query returns direction and correct PnL sign.
- End-to-end: capture SELL trade -> verify settlement cells have negative energy and correct PnL -> verify L3 shows SELL.
- End-to-end: capture BUY + SELL in same portfolio -> verify L1 portfolio summary nets PnL correctly.
- Migration test (if applicable): verify backfill logic sets direction based on quantity sign.

### 12.3 Contract Tests

- `JpaPositionLedgerRepository` contract test: verify `toEntity`/`toDomain` round-trips `direction` correctly.
- `JpaTradeLegRollupRepository` contract test: verify `saveAll`/`findByPortfolio` round-trips `direction` correctly.

---

## S13 -- Constraint Compatibility

| Constraint | Status | Rationale |
|------------|--------|-----------|
| **D-1** Ledger grain = trade-leg x delivery-month; signed qty | **Compatible** | Direction is added as a denormalized attribute. Quantity remains signed, grain unchanged. |
| **D-2** Price = expression reference | **Not applicable** | Price expressions are unchanged. |
| **D-3** Forward marks ephemeral | **Compatible** | `ForwardMarkJob.buildResult()` is modified to use signed energy, but forward marks remain ephemeral (overwrite via `ForwardMarkStore.put()`). No bitemporal change. |
| **D-11** Unified volume: VolumeReference x multiplier | **Compatible** | `VolumeResolver` is unchanged. Energy signing happens downstream of volume resolution, at the settlement cell build site. D-11 is preserved. |
| **D-12** S6b trade_interval_cache: optional, rebuildable | **Not applicable** | Cache structure unchanged. |
| **D-13** Library modules Spring-free | **Compatible** | `TradeDirection` is a plain Java enum in `pv-domain`. No Spring types introduced in any library module. The `TradeCaptureRequest` DTO change is in `pv-app` only. |
| **D-14** Simulator patterns in pv-app only | **Compatible** | No hardcoded tenant IDs introduced. No `hbm2ddl` changes leak into library modules. The `direction` default for `hbm2ddl` (`'BUY'`) is only relevant to the simulator's auto-schema. |
| **Bitemporal** | **Compatible** | Direction is immutable after creation. No in-place mutation. Supersession creates new entries. |
| **AbstractMaterializationJob.execute() is final** | **Compatible** | `execute()` is NOT modified. Only the `buildResult()` hook (which is abstract and overridden by subclasses) is modified. |
| **Outbox pattern (#24)** | **Compatible** | No new direct-produce path. All events flow through outbox. |
| **Idempotent consumers (#26)** | **Compatible** | Consumer idempotency is unaffected. Re-processing produces identical signed values. |
| **Multi-tenancy (P1, TR-032)** | **Compatible** | Direction is per-trade, not per-tenant. All tenant-filtered queries unchanged. |
| **Numeric precision (S5.0)** | **Compatible** | The `signum()` call returns -1, 0, or 1. Multiplying energy by signum does not change precision domain. The result is still rounded via `NumericPrecision.Domain.ENERGY` or `MONETARY` as appropriate. |

---

## S14 -- Open Question Resolutions

### OQ-1: How is energy signed?

**Decision: Option (b) -- sign energy at the settlement cell build site.**

**Rationale:** VolumeResolver (S3) resolves physical volume, which is inherently unsigned. Signing energy based on position direction is a valuation concern (S5a), not a volume concern (S3). Localizing the sign logic to `buildResult()` / `buildSettlementCell()` keeps S3 unchanged and follows the existing separation of concerns.

**Implementation:** In `SettlementMaterializationJob.buildResult()`, `SettlementRevaluationService.buildSettlementCell()`, `ForwardMarkJob.buildResult()`, and `EodStrikeJob.buildResult()`:

```
int directionSign = position.quantity().signum();  // +1 for BUY, -1 for SELL
BigDecimal signedEnergy = volume.energy().multiply(BigDecimal.valueOf(directionSign));
BigDecimal signedVolume = volume.volume().multiply(BigDecimal.valueOf(directionSign));
```

Then use `signedEnergy` in place of `volume.energy()` for all amount calculations:
```
tradeAmount = np.round(price.value().multiply(signedEnergy), MONETARY);
marketAmount = np.round(marketPrice.multiply(signedEnergy), MONETARY);
pnl = np.round(marketAmount.subtract(tradeAmount), MONETARY);
```

**Why `signum(quantity)` and not `direction.sign()`:** The signed quantity is the authoritative computational input (per functional spec section 1.2 and CONTEXT section 2.4). Using `signum(quantity)` means the computation is correct even for legacy entries that predate the `direction` field. The `direction` field is for display/audit only.

**Impact on `SettlementCell` record:** The `volumeMwh` and `volumeMw` fields will now carry signed values. The record definition is unchanged (they are `BigDecimal`). The `amount`, `marketAmount`, and `pnl` fields will also be signed. This is a behavioral change, not a structural one.

### OQ-2: Dashboard quantity display

**Decision: Show signed quantity.**

**Rationale:** The current UI already shows signed quantity (no `Math.abs()` in `NumericCell`). Adding a Direction column alongside signed quantity is redundant but harmless, and it is more informative than showing absolute value because:
1. Users who have internalized the signed convention are not confused.
2. The direction column provides a visual anchor for new users.
3. Showing absolute value would require changing `NumericCell` behavior for one column, adding complexity.

### OQ-3: Data migration for existing positions

**Decision: Option (a) -- migration script backfills based on quantity sign.**

**Rationale:** This is deterministic and consistent. `quantity >= 0` implies BUY; `quantity < 0` implies SELL. After backfill, the column is NOT NULL. The `toDomain()` mapper in `JpaPositionLedgerRepository` includes a fallback for null direction (during the transition window before migration runs) that uses the same sign-based inference.

**Note:** For the simulator (`hbm2ddl.auto=update`), the column is added with a default of `'BUY'`. This is acceptable for the simulator because it uses seed data which is always BUY. In production, the Flyway migration uses the `CASE WHEN` backfill.

### OQ-4: Idempotent re-capture with direction mismatch

**Decision: Option (a) -- return existing entries (current behavior, ignore mismatch).**

**Rationale:** The functional spec recommends option (b) (reject with 409), but this violates the existing idempotency contract of `DefaultTradeCaptureHandler`. The handler currently returns existing entries without inspecting any field for equality. Adding a direction equality check would create an asymmetry (direction is checked but quantity, price expression, etc. are not). If direction mismatch detection is desired, it should be implemented as a general "capture conflict detection" feature covering all fields, not just direction. This is a separate feature.

**The caller is responsible for consistency.** If a caller sends conflicting direction for the same `(tradeId, tradeLegId, tradeVersion)`, the first write wins. This matches the current behavior for all other fields.

### OQ-5: TradeAmendRequest direction

**Decision: Deferred.** The amend flow already creates a new trade version via the same `TradeCapture` command path. When the amend DTO is updated to carry `direction`, it flows through `TradeCapture.direction` naturally. This is a follow-up ticket.

### OQ-6: Should `volumeMwh` on SettlementCell be signed?

**Decision: Yes, sign `volumeMwh` (and `volumeMw`).**

**Rationale:** Per OQ-1, signing energy at the build site means `signedEnergy` flows into the `SettlementCell` constructor as `volumeMwh`. This makes `amount = price * volumeMwh` naturally correct without branching. The rollup aggregation (`RollupMaterializationService`) uses `BigDecimal::add` on `volumeMwh` which correctly nets buy and sell volumes. No downstream consumer in this codebase assumes positive volume -- confirmed by code inspection.

### OQ-7: FR-034 alignment

**Decision: No FR-number amendment proposed.** This feature is additive to FR-034. The `direction` field is a denormalized companion to the signed quantity, not a replacement. A new functional rule (FR-034a) would be appropriate but creating FR numbers is not within the scope of this technical spec. Flagged for functional-expert to formalize if needed.

---

## S15 -- Detailed Change Map

This section provides the precise change description per file for implementation-engineer.

### 15.1 pv-domain changes (library-scope)

| File | Change |
|------|--------|
| `domain/model/TradeDirection.java` | **NEW.** Enum with `BUY`, `SELL`, `sign()` method. Pattern #4. |
| `domain/command/TradeCapture.java` | Add `TradeDirection direction` field (mandatory, between `quantity` and `volumeUnit`). |
| `domain/model/PositionLedgerEntry.java` | Add `TradeDirection direction` field, builder method, accessor, `requireNonNull` in `build()`. |
| `domain/service/DefaultTradeCaptureHandler.java` | In `handle()`: compute `signedQuantity = cmd.quantity().abs().multiply(BigDecimal.valueOf(cmd.direction().sign()))`. Pass `signedQuantity` to builder `.quantity(signedQuantity)`. Pass `cmd.direction()` to builder `.direction(cmd.direction())`. |
| `domain/service/SettlementMaterializationJob.java` | In `buildResult()`: compute `directionSign = position.quantity().signum()`, `signedEnergy = volume.energy().multiply(BigDecimal.valueOf(directionSign))`, `signedVolume = volume.volume().multiply(BigDecimal.valueOf(directionSign))`. Use `signedEnergy` in `tradeAmount` and `marketAmount` calculations. Pass `signedVolume` and `signedEnergy` to `SettlementCell` constructor. |
| `domain/service/SettlementRevaluationService.java` | In `buildSettlementCell()`: identical arithmetic change as `SettlementMaterializationJob.buildResult()`. |
| `domain/service/ForwardMarkJob.java` | In `buildResult()`: `BigDecimal signedEnergy = volume.energy().multiply(BigDecimal.valueOf(position.quantity().signum()))`. `markValue = price.value().multiply(signedEnergy)`. |
| `domain/service/EodStrikeJob.java` | In `buildResult()`: same signing pattern as `ForwardMarkJob`. `markValue = price.value().multiply(signedEnergy)`. |
| `domain/service/RollupMaterializationService.java` | In `buildTradeLegRollupCell()`: pass `pos.direction() != null ? pos.direction().name() : (pos.quantity().signum() >= 0 ? "BUY" : "SELL")` to `TradeLegRollupCell` constructor. |
| `domain/port/repository/TradeLegRollupCell.java` | Add `String direction` field (after `volumeUnit`). |
| `domain/port/service/dashboard/PositionContribution.java` | Add `String direction` field (after `volumeUnit`). |
| `domain/service/DefaultDashboardQueryService.java` | In `positionContributions()` (rollup path): pass `first.direction()` to `PositionContribution`. In `positionContributionsOnTheFly()`: pass `pos.direction() != null ? pos.direction().name() : (pos.quantity().signum() >= 0 ? "BUY" : "SELL")` to `PositionContribution`. |

### 15.2 pv-persistence changes (library-scope)

| File | Change |
|------|--------|
| `persistence/entity/PositionLedgerEntryEntity.java` | Add `@Column(name = "direction", nullable = false, length = 4) private String direction;` with getter/setter. |
| `persistence/adapter/JpaPositionLedgerRepository.java` | `toEntity()`: add `e.setDirection(d.direction().name())`. `toDomain()`: add `.direction(e.getDirection() != null ? TradeDirection.valueOf(e.getDirection()) : (e.getQuantity().signum() >= 0 ? TradeDirection.BUY : TradeDirection.SELL))`. |
| `persistence/adapter/JpaTradeLegRollupRepository.java` | Add `direction` to SELECT list, INSERT column list, VALUES clause, ON CONFLICT DO UPDATE clause. Update `mapToRollupCell()` column index mapping. |

### 15.3 pv-app changes (simulator-scope)

| File | Change |
|------|--------|
| `app/dto/TradeCaptureRequest.java` | Add `String direction` field. In `toCommand()`: add `TradeDirection.valueOf(direction)` argument. |
| `app/dto/dashboard/PositionContributionDto.java` | Add `String direction` field. In `from()`: add `c.direction()` mapping. |
| `app/controller/TradeController.java` | No change (passes through DTO). Validation of direction occurs in `TradeDirection.valueOf()` which throws `IllegalArgumentException` for invalid values, resulting in HTTP 400 via the existing exception handler. |

### 15.4 pv-ui changes (simulator-scope)

| File | Change |
|------|--------|
| `pv-ui/src/schemas/api.ts` | Add `direction: z.enum(["BUY", "SELL"])` to `positionContributionSchema`. |
| `pv-ui/src/components/dashboard/PositionLedger.tsx` | Add `Direction` column after `Leg` column. Render as colored badge: `"BUY"` in green (`text-green-600 bg-green-50`), `"SELL"` in red (`text-red-600 bg-red-50`). Use the existing `StatusBadge` component pattern but with direction-specific styling, or a new `DirectionBadge` component. Column size: 60px. |

---

## S16 -- Implementation Order

The recommended implementation order minimizes risk and allows incremental testing:

1. **Phase 1 (domain model):** `TradeDirection` enum, `PositionLedgerEntry` direction field, `TradeCapture` direction field. Unit test the builder and direction sign logic.
2. **Phase 2 (trade capture):** `DefaultTradeCaptureHandler` sign logic. Unit test BUY/SELL/pre-signed scenarios.
3. **Phase 3 (persistence):** `PositionLedgerEntryEntity`, `JpaPositionLedgerRepository` mapper. Integration test round-trip.
4. **Phase 4 (settlement PnL fix):** `SettlementMaterializationJob.buildResult()`, `SettlementRevaluationService.buildSettlementCell()`. Unit test signed PnL for BUY and SELL.
5. **Phase 5 (forward marks):** `ForwardMarkJob.buildResult()`, `EodStrikeJob.buildResult()`. Unit test signed mark values.
6. **Phase 6 (rollup & dashboard):** `TradeLegRollupCell`, `RollupMaterializationService`, `JpaTradeLegRollupRepository`, `PositionContribution`, `DefaultDashboardQueryService`, `PositionContributionDto`.
7. **Phase 7 (simulator UI):** `TradeCaptureRequest`, `api.ts`, `PositionLedger.tsx`.

---

## S17 -- Open Items

| # | Item | Owner | Blocking? |
|---|------|-------|-----------|
| OI-1 | Flyway migration for `direction` column on `position_ledger_entry` -- belongs to production hosting layer. | Production DBA / hosting team | No (simulator uses hbm2ddl) |
| OI-2 | Flyway migration for `direction` column on `trade_leg_rollup_cell` -- same. | Production DBA / hosting team | No |
| OI-3 | `TradeAmendRequest` DTO should gain `direction` field. Follow-up ticket. | Implementation engineer | No (amend creates new TradeCapture which will require direction) |
| OI-4 | Confirm no downstream system (back office, invoicing) consumes `SettlementCell.volumeMwh` assuming positive values. This was checked within this codebase (confirmed safe) but external consumers are unknown. | Integration / back office team | No (internal codebase is safe) |
| OI-5 | FR-034a formal addition to functional spec. | Functional expert | No (feature is additive) |
| OI-6 | The `PositionLedgerEntryEntity` default for `direction` in hbm2ddl: should be `'BUY'` for simplicity, but this means existing simulator data gets `'BUY'` regardless of quantity sign. Acceptable for simulator. | Implementation engineer | No |
| OI-7 | `EodStrikeJob.buildResult()` uses `price.value().multiply(volume.energy())` without `NumericPrecision` rounding. This is a pre-existing gap (not introduced by this feature) but should be fixed in the same pass since we are modifying that line. | Implementation engineer | No |
