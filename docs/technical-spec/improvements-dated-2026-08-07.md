# Technical Specification — Improvements 2026-08-07

## Overview

Nine enhancements implemented on branch `spring-app-3`, covering the settlement valuation subsystem (S5a), Kafka async pipeline, materialization observability, price evaluation strategy, rollup materialization, query-time aggregation, service layer abstraction, and S8 dependency-index-based blast-radius optimization:

1. **Kafka Listener Refactor & Per-Entry Event Model** — Replace daemon-thread poll loop with Spring `@KafkaListener`; change from one event per trade to one per position entry
2. **Materialization Execution Timing** — Thread-safe instrumentation separating volume resolution, market data lookup, expression compute, and flush timings
3. **Strategy Pattern: Rule-Engine-Based Price Evaluator** — MVEL-backed alternative `PriceEvaluator` switchable via `pv.pricing.strategy`
4. **Market Price Expression & PnL** — Per-trade mark-to-market price with PnL at materialization time
5. **Interval-Level Settlement Revaluation** — Event-driven recomputation of settlement cells at sub-month granularity
6. **S7 Rollup Materialization & PositionMonthSummary** — Materialized rollups from settlement cells with market value/PnL; query-time monthly aggregation
7. **Service Layer Abstraction** — Controllers decoupled from repository ports via service interfaces
8. **Appendix: System Flow Diagrams & Test Trigger APIs** — API and Kafka event flow reference with sample payloads; REST endpoints for triggering events through the full outbox → Kafka pipeline
9. **S8 Dependency-Index Blast-Radius Optimization** — `MarketDataUpdatedConsumer` uses dependency index instead of brute-force position scan

All changes are backward-compatible.

> **Supersedes** stale sections of `TECH-SPEC-spring-boot-service-module-v1.0.md` §5.4, §8.1–§8.4, §14.1 (old Kafka daemon-thread implementation, `PositionCaptured` event name, `DefaultPriceEvaluator` class name, `VolumeSeriesRepository`-based idempotency check).

---

## Enhancement 1: Kafka Listener Refactor & Per-Entry Event Model

> Supersedes `TECH-SPEC-spring-boot-service-module-v1.0.md` §5.4 (KafkaConfig), §8.1–§8.4 (async pipeline), §14.1 (startup log).

### 1.1 Problem

The original Kafka consumer used a manual daemon thread with `SmartLifecycle`, a raw `KafkaConsumer` poll loop, hand-rolled JSON parsing, `enable.auto.commit=true`, and no retry or dead-letter queue. Additionally, the single `PositionCaptured` event per trade meant all monthly blocks for a multi-month trade were settled sequentially by one consumer thread.

### 1.2 Changes

#### Event model: `PositionCaptured` → `PositionEntryCaptured`

**Old:** One `PositionCaptured(tradeId, tradeLegId, tradeVersion, tenantId)` event per trade. The consumer had to re-query the DB by `(tradeId, tradeLegId, tradeVersion)` to find all position entries.

**New:** One `PositionEntryCaptured(tenantId, positionId, eventTime)` per position ledger entry (per delivery-month block). Each event carries the `positionId` UUID directly — the consumer loads a single entry with no re-query. Multi-month trades emit N events (one per month), enabling parallel settlement across Kafka partitions.

```java
// pv-domain/.../event/PositionEntryCaptured.java
public record PositionEntryCaptured(
    String tenantId,
    UUID positionId,
    Instant eventTime
) {}
```

`DefaultTradeCaptureHandler.handle()` now publishes one `PositionEntryCaptured` per saved `PositionLedgerEntry`:

```java
entries.forEach(entry ->
    eventPublisher.publish(new PositionEntryCaptured(
        cmd.tenantId(), entry.id(), now)));
```

#### Outbox relay: convention-based topic routing

`OutboxRelayProducer.relay()` derives the Kafka topic from the event type column:

```java
String topic = topicPrefix + entry.getEventType();
// "posval." + "PositionEntryCaptured" → "posval.PositionEntryCaptured"
```

This makes the relay generic — any new event type written to the outbox by `OutboxDomainEventPublisher` is automatically routed without relay code changes.

#### `KafkaConfig.java` — Spring Kafka typed consumer

Replaced the raw `KafkaConsumer<String, String>` bean with Spring Kafka infrastructure:

| Bean | Purpose |
|------|---------|
| `ConsumerFactory<String, PositionEntryCaptured>` | `ErrorHandlingDeserializer` wrapping `JsonDeserializer`, trusted packages `com.power.posval.domain.event`, `enable.auto.commit=false` |
| `ConcurrentKafkaListenerContainerFactory` | `AckMode.MANUAL_IMMEDIATE`, `ExponentialBackOff(1s → 2s → 4s, max 3)`, `DeadLetterPublishingRecoverer` → `<topic>.DLQ`, `DeserializationException` not retryable |
| `KafkaTemplate<String, String>` | Used by `DeadLetterPublishingRecoverer` for DLQ publishing |

#### `TradeCapturedKafkaListener.java` — declarative listener

Replaced the `SmartLifecycle` daemon thread + poll loop with a simple `@Component`:

```java
@KafkaListener(
    topics = "posval.PositionEntryCaptured",
    containerFactory = "positionEntryCapturedListenerFactory"
)
public void onPositionEntryCaptured(ConsumerRecord<String, PositionEntryCaptured> record,
                                     Acknowledgment ack) {
    PositionEntryCaptured event = record.value();
    try {
        tenantContext.setTenant(event.tenantId());
        txExecutor.run(() -> tradeCapturedConsumer.handle(event));
        ack.acknowledge();
    } finally {
        tenantContext.clear();
    }
}
```

Error handling (retry, backoff, DLQ) is entirely handled by the container factory's `DefaultErrorHandler`. No try/catch, no swallowing.

#### `TradeCapturedConsumer` — idempotency change

**Old:** `alreadyProcessed()` checked `VolumeSeriesRepository.existsByTradeIdAndTradeVersion()`.

**New:** Checks `cellRepo.existsByPositionId(event.tenantId(), event.positionId())` — aligned with the per-entry event model. If settlement cells already exist for this position, the event is skipped.

### 1.3 Files

| Module | File | Action |
|--------|------|--------|
| pv-domain | `event/PositionEntryCaptured.java` | **New** (replaces `PositionCaptured`) |
| pv-domain | `service/DefaultTradeCaptureHandler.java` | Modified — emits per-entry events |
| pv-kafka | `TradeCapturedConsumer.java` | Modified — `PositionEntryCaptured` type, `existsByPositionId` idempotency |
| pv-kafka | `OutboxRelayProducer.java` | Modified — convention-based `topicPrefix + eventType` routing |
| pv-app | `kafka/TradeCapturedKafkaListener.java` | Rewritten — `@KafkaListener` replacing daemon thread |
| pv-app | `config/KafkaConfig.java` | Rewritten — `ConsumerFactory`, `ConcurrentKafkaListenerContainerFactory`, DLQ, backoff |

---

## Enhancement 2: Materialization Execution Timing

### 2.1 Problem

`AbstractMaterializationJob.execute()` had no observability into where time was spent. For a 5-year PPA with 35,000+ 15-minute intervals, understanding the bottleneck split between volume resolution, market data lookups (cache + DB), expression evaluation (CPU), and persistence flush was impossible.

### 2.2 Design

Three new classes in `pv-domain/.../service/`:

#### `PriceCalcTimingStats`

Mutable accumulator reset per position execution. Tracks lookup count and total nanoseconds.

```java
public final class PriceCalcTimingStats {
    private int lookupCount;
    private long lookupTotalNanos;

    public void recordLookup(long nanos) { lookupCount++; lookupTotalNanos += nanos; }
    public void reset() { lookupCount = 0; lookupTotalNanos = 0; }
    public int lookupCount() { return lookupCount; }
    public long lookupTotalMs() { return lookupTotalNanos / 1_000_000; }
}
```

#### `InstrumentedMarketDataPort`

Decorator wrapping `MarketDataPort`. Uses `ThreadLocal<PriceCalcTimingStats>` so concurrent Kafka consumer threads accumulate independently. Intercepts all 7 lookup methods (`lookupFixing`, `lookupIndex`, `lookupForwardCurve`, `lookupFxRate`, `lookupAtVersion`, `lookupVolSurface`, `lookupSpread`), recording `System.nanoTime()` deltas.

```java
public class InstrumentedMarketDataPort implements MarketDataPort {
    private final MarketDataPort delegate;
    private final ThreadLocal<PriceCalcTimingStats> threadStats =
        ThreadLocal.withInitial(PriceCalcTimingStats::new);

    public PriceCalcTimingStats stats() { return threadStats.get(); }
    public void resetStats() { threadStats.get().reset(); }
    public void clearStats() { threadStats.remove(); }

    @Override
    public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupFixing(series, intervalStart);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }
    // ... same pattern for all 7 methods
}
```

#### `AbstractMaterializationJob` — instrumented `execute()`

The constructor wraps the injected `MarketDataPort` in `InstrumentedMarketDataPort`. The `execute()` method (still `final`) now logs three timing phases:

```
resolveVolume(): 12 ms, intervals=2976
priceCalc(): 340 ms (lookup=210 ms, compute=130 ms, lookups=5952), results=2976
flushResults(): 45 ms
```

The `lookup` vs `compute` breakdown is derived as: `computeMs = totalPriceCalcMs - lookupTotalMs`. Stats are reset before the price calc loop and cleared in a `finally` block.

### 2.3 Files

| Module | File | Action |
|--------|------|--------|
| pv-domain | `service/PriceCalcTimingStats.java` | **New** |
| pv-domain | `service/InstrumentedMarketDataPort.java` | **New** |
| pv-domain | `service/AbstractMaterializationJob.java` | Modified — wraps MarketDataPort, logs 3-phase timings |

---

## Enhancement 3: Strategy Pattern — Rule-Engine-Based Price Evaluator

> Supersedes `TECH-SPEC-spring-boot-service-module-v1.0.md` §5.2 (`DefaultPriceEvaluator` → renamed to `PriceExpressionBasedEvaluator`).

### 3.1 Problem

The expression-tree walker (`PriceExpressionBasedEvaluator`, formerly `DefaultPriceEvaluator`) is the only `PriceEvaluator` implementation. For tenants who want configurable pricing rules without code changes, an MVEL-based rule engine alternative is needed. Both strategies must produce bit-identical results for the same inputs.

### 3.2 Strategy Switch

`DomainServiceConfig.java` conditionally creates the evaluator:

```java
@Bean
@ConditionalOnProperty(name = "pv.pricing.strategy", havingValue = "expression", matchIfMissing = true)
public PriceEvaluator priceEvaluator(NumericPrecision np) {
    return new PriceExpressionBasedEvaluator(np);  // default
}
```

When `pv.pricing.strategy=rule-engine`, `RuleEngineConfig` activates and provides a `@Primary` bean that overrides the expression-based one.

### 3.3 `RuleEngineConfig` — `pv-app/.../config/RuleEngineConfig.java`

`@ConditionalOnProperty(name = "pv.pricing.strategy", havingValue = "rule-engine")`. Builds the rule engine from JSON:

1. Loads rules from `stub/price-rules.json` via `JsonRuleDao`
2. Populates `InMemoryRuleCache`, marks warm
3. Loads rulesets from `stub/price-rulesets.json` via `JsonRuleSetDao`
4. Builds `CacheBackedRuleResolver` and indexes rulesets
5. Creates `DefaultRuleEngine` with `DefaultDecisionEvaluator`, `DefaultConflictResolver`, `MvelEvaluator`
6. Creates `@Primary` `RuleEngineBasedEvaluator` bean with reverse-map `PriceExpression → exprId`

### 3.4 `PriceRuleDefinition` — `pv-app/.../pricing/PriceRuleDefinition.java`

Maps a price expression ID to its MVEL formula and data bindings:

```java
public record PriceRuleDefinition(
    String priceExpressionId,
    String mvelFormula,
    Map<String, BigDecimal> constants,
    Map<String, SeriesBinding> seriesBindings
) {
    public record SeriesBinding(
        String series,
        String settlementSeries,   // nullable — purpose-based override (FR-048e)
        LookupType lookupType,     // FIXING, INDEX, FX_RATE
        String refMonthExpression  // nullable — for INDEX lookups
    ) {}

    public enum LookupType { FIXING, INDEX, FX_RATE }
}
```

Loaded from `stub/price-rule-definitions.json` at startup.

### 3.5 `RuleEngineBasedEvaluator` — `pv-app/.../pricing/RuleEngineBasedEvaluator.java`

Implements `PriceEvaluator`. Evaluation flow:

1. **Reverse-lookup** expression ID from the `PriceExpression` object (via map built at startup)
2. **Load** `PriceRuleDefinition` by expression ID
3. **Pre-resolve** all market data series into `RuleContext` as `BigDecimal` — handles purpose-based series selection (FR-048e: `SETTLEMENT` purpose uses `settlementSeries` if available)
4. **Inject** constants and `MvelPrecisionHelper` as `"np"` into context
5. **Evaluate** via `ruleEngine.apply(WorkflowPoint.SETTLEMENT, context)`
6. **Extract** `result.decimal("price")`, round to `PRICE` domain, return `PriceResolution`

### 3.6 `MvelPrecisionHelper` — `pv-app/.../pricing/MvelPrecisionHelper.java`

Injected into MVEL context as `"np"`. Each method mirrors the rounding behavior of a specific expression-tree node type to ensure bit-identical results:

| Tree Node | Helper Method | Domain |
|-----------|---------------|--------|
| `Divide` | `np.divide(a, b)` | INTERMEDIATE (scale 10) |
| `Escalate(base × ratio)` | `np.escalate(b, r)` | PRICE (scale 8) |
| `Multiply` | `np.multiply(a, b)` | INTERMEDIATE (scale 10) |
| `FxConvert(val × rate)` | `np.monetary(v)` | MONETARY (scale 4) |
| `Clamp(min, max, val)` | `np.clamp(lo, hi, v)` | no rounding |

Example MVEL formula equivalent to the CPI-escalated collar expression (EXPR-4):

```
epex < 0 ? np.price(0) : np.clamp(floor, cap, np.escalate(basePrice, np.divide(cpiCurrent, cpiBase)))
```

### 3.7 Tests

`RuleEngineBasedEvaluatorTest` (20 tests) — verifies bit-identical output for all expression types (fixed, index+spread, collar, CPI-escalated) against `PriceExpressionBasedEvaluator`. `MvelPrecisionHelperTest` (11 tests) — rounding correctness for each helper method.

### 3.8 Files

| Module | File | Action |
|--------|------|--------|
| pv-app | `config/RuleEngineConfig.java` | **New** — conditional Spring config |
| pv-app | `pricing/RuleEngineBasedEvaluator.java` | **New** — MVEL-backed `PriceEvaluator` |
| pv-app | `pricing/PriceRuleDefinition.java` | **New** — formula + bindings record |
| pv-app | `pricing/MvelPrecisionHelper.java` | **New** — rounding bridge for MVEL context |
| pv-app | `config/DomainServiceConfig.java` | Modified — `@ConditionalOnProperty` on `priceEvaluator` bean |
| pv-domain | `service/PriceExpressionBasedEvaluator.java` | Renamed from `DefaultPriceEvaluator` |

---

## Enhancement 4: Market Price Expression & PnL

### 4.1 Problem

Each position carried a single `priceExpressionId` representing the **contractual trade price**. There was no mechanism to evaluate a **market price** alongside it, making mark-to-market valuation and PnL computation impossible at the settlement cell level.

### 4.2 Design Decisions

| Decision | Rationale |
|----------|-----------|
| Per-trade `marketPriceExpressionId` (nullable) | Simple, explicit; no zone-level fallback logic needed |
| Keep existing `price`/`amount` field names | Backward-compatible; no rename migration |
| Add `marketPrice`/`marketAmount`/`pnl` alongside | Parallel structure, nullable for trades without market expression |
| PnL stored at materialization time | No on-read computation; consistent with settlement cell immutability |

### 4.3 Domain Model Changes

#### `PositionLedgerEntry` — `pv-domain/.../model/PositionLedgerEntry.java`

Added `marketPriceExpressionId` (nullable `UUID`) field, accessor, and builder setter. No `requireNonNull` — field is optional.

```
private final UUID marketPriceExpressionId;  // nullable — market-to-market price expression
```

#### `TradeCapture` / `TradeAmend` commands

Both records gained a `UUID marketPriceExpressionId` field after `priceExpressionId`. In `TradeAmend`, null means "unchanged" (same nullable-merge pattern as `priceExpressionId`).

#### `SettlementCell` — `pv-domain/.../model/SettlementCell.java`

Three nullable fields added after `amount`:

```java
BigDecimal marketPrice,      // mark-to-market price per interval
BigDecimal marketAmount,     // marketPrice × energy
BigDecimal pnl,              // marketAmount - amount (trade amount)
```

### 4.4 Persistence Layer

#### `PositionLedgerEntryEntity`

```java
@Column(name = "market_price_expression_id")
private UUID marketPriceExpressionId;
```

#### `SettlementCellEntity`

```java
@Column(name = "market_price", precision = 15, scale = 8)
private BigDecimal marketPrice;

@Column(name = "market_amount", precision = 18, scale = 4)
private BigDecimal marketAmount;

@Column(name = "pnl", precision = 18, scale = 4)
private BigDecimal pnl;
```

#### Schema (`init-schemas.sql`)

```sql
-- position.position_ledger_entry
market_price_expression_id UUID

-- valuation.settlement_cell
market_price   NUMERIC(15, 8)
market_amount  NUMERIC(18, 4)
pnl            NUMERIC(18, 4)
```

#### DB Migration (existing databases)

```sql
ALTER TABLE position.position_ledger_entry
  ADD COLUMN IF NOT EXISTS market_price_expression_id UUID;

ALTER TABLE valuation.settlement_cell
  ADD COLUMN IF NOT EXISTS market_price NUMERIC(15, 8),
  ADD COLUMN IF NOT EXISTS market_amount NUMERIC(18, 4),
  ADD COLUMN IF NOT EXISTS pnl NUMERIC(18, 4);
```

### 4.5 Settlement Materialization — Dual-Price Evaluation

`SettlementMaterializationJob.buildResult()` now performs dual evaluation when `position.marketPriceExpressionId() != null`:

```
1. Evaluate trade price (existing path — position.priceExpressionId())
2. Compute tradeAmount = round(tradePrice × energy, MONETARY)
3. If marketPriceExpressionId is set:
   a. Evaluate market price via same priceEvaluator.evaluate(expr, interval, SETTLEMENT)
   b. Compute marketAmount = round(marketPrice × energy, MONETARY)
   c. Compute pnl = round(marketAmount - tradeAmount, MONETARY)
   d. Merge activeLeaves and inputVersionSet from both resolutions
4. Construct SettlementCell with all fields
```

When `marketPriceExpressionId` is null, `marketPrice`, `marketAmount`, and `pnl` are all null — backward-compatible.

### 4.6 Handler Wiring

- **`DefaultTradeCaptureHandler`** — passes `cmd.marketPriceExpressionId()` to builder
- **`DefaultTradeAmendHandler`** — nullable-merge: `cmd.marketPriceExpressionId() != null ? cmd : existing`

### 4.7 REST DTOs

| DTO | Field Added |
|-----|-------------|
| `TradeCaptureRequest` | `String marketPriceExpressionId` — parsed to UUID if non-null |
| `TradeAmendRequest` | `String marketPriceExpressionId` — parsed to UUID if non-null |
| `PositionLedgerEntryDto` | `UUID marketPriceExpressionId` |
| `SettlementCellDto` | `BigDecimal marketPrice`, `BigDecimal marketAmount`, `BigDecimal pnl` |

### 4.8 Tests Added/Updated

| Test | What was verified |
|------|-------------------|
| `SettlementCellTest.marketPriceAndPnlWhenPresent` | Record construction with market fields |
| `SettlementMaterializationJobTest.dualPriceExpressions_producesMarketPriceAndPnl` | Trade=85.00, Market≈28.06, PnL negative |
| `DefaultTradeCaptureHandlerTest.marketPriceExpressionId_flowsThrough` | Field propagates to ledger entry |
| `DefaultTradeAmendHandlerTest` (2 new tests) | Preserve existing / update with new market expr |
| `TradeToSettlementIntegrationTest.fullPipeline_dualExpressions_producesPnl` | End-to-end: capture → settle → verify PnL |

### 4.9 Files Changed

| Module | File | Action |
|--------|------|--------|
| pv-domain | `model/PositionLedgerEntry.java` | Modified — field, accessor, builder |
| pv-domain | `command/TradeCapture.java` | Modified — added record field |
| pv-domain | `command/TradeAmend.java` | Modified — added record field |
| pv-domain | `model/SettlementCell.java` | Modified — 3 new record fields |
| pv-domain | `service/SettlementMaterializationJob.java` | Modified — dual evaluation in `buildResult()` |
| pv-domain | `service/DefaultTradeCaptureHandler.java` | Modified — wire field |
| pv-domain | `service/DefaultTradeAmendHandler.java` | Modified — nullable-merge |
| pv-persistence | `entity/PositionLedgerEntryEntity.java` | Modified — column + getter/setter |
| pv-persistence | `entity/SettlementCellEntity.java` | Modified — 3 columns + getters/setters |
| pv-persistence | `adapter/JpaPositionLedgerRepository.java` | Modified — mapper update |
| pv-persistence | `adapter/JpaSettlementCellRepository.java` | Modified — mapper update |
| pv-app | `dto/TradeCaptureRequest.java` | Modified — field + toCommand |
| pv-app | `dto/TradeAmendRequest.java` | Modified — field + toCommand |
| pv-app | `dto/PositionLedgerEntryDto.java` | Modified — field + from() |
| pv-app | `dto/SettlementCellDto.java` | Modified — 3 fields + from() |
| pv-app | `resources/db/init-schemas.sql` | Modified — 4 new columns |

---

## Enhancement 5: Interval-Level Settlement Revaluation

### 5.1 Problem

Settlement materialization runs at **monthly granularity** — triggered once per position ledger entry (one per trade-leg per delivery month) via `PositionEntryCaptured` events. When market data (fixings, forward curves) or volume series (forecasts, actuals) update after initial materialization, the affected settlement cells become stale with no mechanism to recompute them.

### 5.2 Design Decisions

| Decision | Rationale |
|----------|-----------|
| Separate path from initial materialization | `AbstractMaterializationJob.execute()` is `final` — template method not extensible for sub-month scope |
| Delete-then-insert (upsert) semantics | Replace stale cells on the latest position; no version accumulation for revaluation |
| Internal `SettlementRevaluationRequested` event | Normalizes both triggers (market data, volume) into a single shape; decouples trigger detection from revaluation execution |
| Revaluation range clamped to position delivery boundaries | Prevents processing intervals outside the position's actual delivery window |
| Outbox relay convention for topic routing | `posval.SettlementRevaluationRequested` — no relay code changes needed |

### 5.3 Architecture

```
MarketDataUpdated / VolumeSuperseded event
  └─> Extended consumer (existing)
        ├─ Cache invalidation (existing, unchanged)
        └─ Find affected positions → publish SettlementRevaluationRequested per position
              └─> [Kafka: posval.SettlementRevaluationRequested]
                    └─> SettlementRevaluationConsumer (new)
                          └─> SettlementRevaluationService.revalue(position, start, end)
                                ├─ Clamp range to position delivery boundaries
                                ├─ Resolve volume for sub-range
                                ├─ Evaluate price per interval (trade + market)
                                ├─ Delete old cells for [position, start, end)
                                ├─ Save new cells
                                └─ Publish SettlementComputed events
```

### 5.4 Domain Event

```java
// pv-domain/.../event/SettlementRevaluationRequested.java
public record SettlementRevaluationRequested(
    String tenantId,
    UUID positionId,
    Instant intervalStart,   // half-open [start, end)
    Instant intervalEnd,
    String triggerType,      // "MARKET_DATA" or "VOLUME_SUPERSEDED"
    Instant eventTime
) {}
```

### 5.5 Repository Port Extensions

#### `SettlementCellRepository` — delete for upsert

```java
/**
 * Delete settlement cells for a position whose intervalStart falls within [start, end).
 * Used by revaluation to replace stale cells with fresh computations.
 */
int deleteByPositionAndInterval(String tenantId, UUID positionId,
                                 Instant intervalStart, Instant intervalEnd);
```

JPA implementation uses:
```sql
DELETE FROM SettlementCellEntity e
WHERE e.tenantId = :tenantId AND e.positionId = :positionId
  AND e.intervalStart >= :intervalStart AND e.intervalStart < :intervalEnd
```

> **Note:** This is the first `DELETE` operation on settlement cells. The existing append-only pattern (TR-011) applies to the initial materialization path; revaluation explicitly needs replacement semantics. The delete predicate uses `intervalStart` containment (not overlap) to avoid touching boundary cells.

#### `PositionLedgerRepository` — volume-series-key lookup

```java
/**
 * Current-knowledge entries referencing a specific volume series key
 * with delivery range overlap. Used by VolumeSuperseded revaluation.
 */
List<PositionLedgerEntry> findCurrentByVolumeSeriesKeyAndDeliveryRange(
    String volumeSeriesKey, Instant deliveryStart, Instant deliveryEnd);
```

JPA implementation:
```sql
SELECT e FROM PositionLedgerEntryEntity e
WHERE e.volumeSeriesKey = :seriesKey
  AND e.deliveryStart < :deliveryEnd AND e.deliveryEnd > :deliveryStart
  AND e.knownTo IS NULL
ORDER BY e.tradeLegId, e.deliveryStart
```

### 5.6 `SettlementRevaluationService`

**New domain service:** `pv-domain/.../service/SettlementRevaluationService.java`

Same constructor dependencies as `SettlementMaterializationJob`: `VolumeResolver`, `PriceEvaluator`, `MarketDataPort`, `PriceExpressionRepository`, `SettlementCellRepository`, `DomainEventPublisher`, `NumericPrecision`.

**`revalue(PositionLedgerEntry position, Instant intervalStart, Instant intervalEnd)`:**

1. **Clamp** range to `[max(start, deliveryStart), min(end, deliveryEnd))` — no-op if empty
2. **Resolve volume** via `volumeResolver.resolve(ref, effectiveStart, effectiveEnd, SETTLEMENT)` — returns 15-min `VolumeRecord` list for just the sub-range
3. **Evaluate & build** — for each `VolumeRecord`, evaluate trade price (and market price if `marketPriceExpressionId` is set), build `SettlementCell` with PnL
4. **Delete old cells** — `cellRepo.deleteByPositionAndInterval(tenantId, positionId, effectiveStart, effectiveEnd)`
5. **Save new cells** — `cellRepo.saveAll(newCells)`
6. **Publish** `SettlementComputed` events

The `buildSettlementCell()` and `evaluatePrice()` helpers mirror `SettlementMaterializationJob.buildResult()` and `evaluatePrice()`. This duplication is intentional — `AbstractMaterializationJob` is a sealed template (`final execute()`), and the two paths may diverge in the future.

### 5.7 Consumer Extensions

#### `MarketDataUpdatedConsumer`

Extended with `DependencyIndex` (S8) and `DomainEventPublisher` dependencies. After cache invalidation, when `affectedRangeStart`/`affectedRangeEnd` are non-null, uses the S8 dependency index to find only positions whose price expressions actually reference the changed series (FR-103 blast-radius optimization):

```java
List<UUID> affectedPositionIds = dependencyIndex.findAffectedPositionIds(
    event.tenantId(), event.series(),
    event.affectedRangeStart(), event.affectedRangeEnd());
for (UUID positionId : affectedPositionIds) {
    eventPublisher.publish(new SettlementRevaluationRequested(
        event.tenantId(), positionId,
        event.affectedRangeStart(), event.affectedRangeEnd(),
        "MARKET_DATA", event.eventTime()));
}
```

This replaces the earlier brute-force approach (`findAllByDeliveryRange`) which would have triggered revaluation for **all** positions in the delivery range, regardless of whether their price expressions referenced the changed series. See Enhancement 9 for details.

Full-series invalidations (null range) do not trigger revaluation — no interval bounds to scope the recomputation.

#### `VolumeSupersededConsumer`

Extended with `PositionLedgerRepository` and `DomainEventPublisher`. After cache invalidation:

```java
Instant rangeStart = event.affectedRange().start().toInstant();
Instant rangeEnd = event.affectedRange().end().toInstant();
List<PositionLedgerEntry> affected =
    ledgerRepo.findCurrentByVolumeSeriesKeyAndDeliveryRange(
        event.seriesKey().value(), rangeStart, rangeEnd);
for (PositionLedgerEntry pos : affected) {
    eventPublisher.publish(new SettlementRevaluationRequested(...));
}
```

Uses the new `findCurrentByVolumeSeriesKeyAndDeliveryRange()` to find positions by series key + delivery overlap rather than tenant-wide `findAllByDeliveryRange()`.

### 5.8 `SettlementRevaluationConsumer`

**New:** `pv-kafka/.../SettlementRevaluationConsumer.java`

Extends `IdempotentConsumer<SettlementRevaluationRequested>`. `alreadyProcessed()` returns `false` — revaluation is idempotent (delete + re-insert produces identical cells). `process()` loads position by ID from `PositionLedgerRepository`, delegates to `SettlementRevaluationService.revalue()`.

### 5.9 Spring Kafka Wiring

#### `KafkaConfig.java`

Added (following the `positionEntryCapturedConsumerFactory`/`positionEntryCapturedListenerFactory` pattern):

- `ConsumerFactory<String, SettlementRevaluationRequested> revaluationConsumerFactory` — consumer group `pv-revaluation-consumer`, typed `JsonDeserializer` for `SettlementRevaluationRequested`
- `ConcurrentKafkaListenerContainerFactory revaluationListenerFactory` — `MANUAL_IMMEDIATE` ack, exponential backoff (1s → 2s → 4s, max 3 retries), DLQ routing to `posval.SettlementRevaluationRequested.DLQ`
- `SettlementRevaluationConsumer` bean

#### `DomainServiceConfig.java`

Added `SettlementRevaluationService` bean with same dependencies as `SettlementMaterializationJob`.

#### `SettlementRevaluationKafkaListener`

**New:** `pv-app/.../kafka/SettlementRevaluationKafkaListener.java`

`@KafkaListener(topics = "posval.SettlementRevaluationRequested", containerFactory = "revaluationListenerFactory")` — follows `TradeCapturedKafkaListener` pattern: set tenant context, run in transaction, acknowledge on success.

### 5.10 Guice Module Updates

- `DomainModule` — `bind(SettlementRevaluationService.class).in(Singleton.class)`
- `KafkaModule` — `bind(SettlementRevaluationConsumer.class).in(Singleton.class)`

### 5.11 Tests

| Test | Cases |
|------|-------|
| `SettlementRevaluationServiceTest` (new, 4 tests) | Happy path (delete + create), range clamping, no overlap = no-op, market price PnL |
| `SettlementRevaluationConsumerTest` (new, 2 tests) | Loads position + calls service, missing position = no-op |
| `MarketDataUpdatedConsumerTest` (updated + 2 new) | Existing cache tests updated for new constructor; new: range update publishes reval requests, full-series does not |

### 5.12 Files Changed

| Module | File | Action |
|--------|------|--------|
| pv-domain | `event/SettlementRevaluationRequested.java` | **New** |
| pv-domain | `service/SettlementRevaluationService.java` | **New** |
| pv-domain | `port/repository/SettlementCellRepository.java` | Modified — `deleteByPositionAndInterval()` |
| pv-domain | `port/repository/PositionLedgerRepository.java` | Modified — `findCurrentByVolumeSeriesKeyAndDeliveryRange()` |
| pv-persistence | `adapter/JpaSettlementCellRepository.java` | Modified — implement delete |
| pv-persistence | `adapter/JpaPositionLedgerRepository.java` | Modified — implement series-key query |
| pv-kafka | `MarketDataUpdatedConsumer.java` | Modified — position lookup + reval publish |
| pv-kafka | `VolumeSupersededConsumer.java` | Modified — position lookup + reval publish |
| pv-kafka | `SettlementRevaluationConsumer.java` | **New** |
| pv-app | `kafka/SettlementRevaluationKafkaListener.java` | **New** |
| pv-app | `config/KafkaConfig.java` | Modified — consumer factory, listener factory, bean |
| pv-app | `config/DomainServiceConfig.java` | Modified — service bean |
| pv-guice | `DomainModule.java` | Modified — bind service |
| pv-guice | `KafkaModule.java` | Modified — bind consumer |

---

## Combined Impact Summary

### New Files

| Enh. | File | Purpose |
|------|------|---------|
| 1 | `PositionEntryCaptured.java` | Per-entry event replacing `PositionCaptured` |
| 2 | `InstrumentedMarketDataPort.java` | ThreadLocal timing decorator for `MarketDataPort` |
| 2 | `PriceCalcTimingStats.java` | Mutable accumulator: lookup count + nanos |
| 3 | `RuleEngineConfig.java` | Conditional Spring config for MVEL rule engine |
| 3 | `RuleEngineBasedEvaluator.java` | MVEL-backed `PriceEvaluator` |
| 3 | `PriceRuleDefinition.java` | Formula + constants + series bindings record |
| 3 | `MvelPrecisionHelper.java` | Rounding bridge for MVEL context |
| 5 | `SettlementRevaluationRequested.java` | Internal revaluation trigger event |
| 5 | `SettlementRevaluationService.java` | Core revaluation: clamp → resolve → evaluate → delete → save → publish |
| 5 | `SettlementRevaluationConsumer.java` | Kafka consumer: load position, delegate to service |
| 5 | `SettlementRevaluationKafkaListener.java` | Spring Kafka listener: tenant context, transaction, ack |

### Key Modified Files

| Enh. | File | Change |
|------|------|--------|
| 1 | `TradeCapturedKafkaListener.java` | Rewritten — `@KafkaListener` replacing daemon thread |
| 1 | `KafkaConfig.java` | Rewritten — `ConsumerFactory`, DLQ, backoff |
| 1 | `TradeCapturedConsumer.java` | Per-entry event, `existsByPositionId` idempotency |
| 1 | `OutboxRelayProducer.java` | Convention-based topic routing |
| 2 | `AbstractMaterializationJob.java` | Wraps `MarketDataPort`, logs 3-phase timings |
| 3 | `DomainServiceConfig.java` | `@ConditionalOnProperty` on evaluator bean |
| 3 | `PriceExpressionBasedEvaluator.java` | Renamed from `DefaultPriceEvaluator` |
| 4 | `PositionLedgerEntry.java` | `marketPriceExpressionId` field |
| 4 | `SettlementCell.java` | `marketPrice`, `marketAmount`, `pnl` fields |
| 4 | `SettlementMaterializationJob.java` | Dual-price evaluation in `buildResult()` |
| 5 | `MarketDataUpdatedConsumer.java` | Position lookup + reval event publish |
| 5 | `VolumeSupersededConsumer.java` | Position lookup + reval event publish |

See per-enhancement file tables for complete lists.

### Test Coverage (as of Enhancement 5)

- **pv-domain**: 241 tests
- **pv-kafka**: 15 tests
- **pv-app**: 31 tests (including 20 `RuleEngineBasedEvaluatorTest` + 11 `MvelPrecisionHelperTest`)
- **pv-integration-tests**: 5 tests
- **Total**: 292 tests, all passing

### Backward Compatibility

- Default `pv.pricing.strategy=expression` — rule engine opt-in only
- Existing trades without `marketPriceExpressionId` → `marketPrice`/`pnl` are null
- Initial materialization path (`TradeCapturedConsumer` → `SettlementMaterializationJob.execute()`) unchanged
- All new DB columns are nullable — no migration risk
- Kafka topic change (`posval.PositionCaptured` → `posval.PositionEntryCaptured`) requires consumers to subscribe to the new topic name

---

## Enhancement 6: S7 Rollup Materialization & PositionMonthSummary

### 6.1 Problem

The existing `RollupRepository` only supported `REFRESH MATERIALIZED VIEW CONCURRENTLY` — a PostgreSQL-side operation with no application-level control. `settledValue` and `forwardMarkValue` in `RollupCell` were hardcoded to `BigDecimal.ZERO`. Additionally, there was no way to get aggregated position-level totals (total MWh, total amount, PnL) for a delivery month without loading and summing all 15-min settlement cells client-side.

### 6.2 Design Decisions

| Decision | Rationale |
|----------|-----------|
| Two complementary approaches | Materialized rollup (S7) for cross-position aggregation; query-time summary for per-position monthly view |
| `RollupMaterializationService` in domain layer | Keeps aggregation logic testable without a database |
| Upsert semantics (ON CONFLICT DO UPDATE) | Incremental refresh — re-derive from settlement cells idempotently |
| `PositionMonthSummary` computed via SQL GROUP BY | Avoids loading millions of 15-min cells into memory for aggregation |
| FR-035 aggregation rules | MW = time-weighted average; MWh = sum; amounts = sum |

### 6.3 RollupCell — Enhanced Model

Two new fields added to `RollupCell` record:

```java
BigDecimal marketValue,   // sum of marketAmount from settlement cells
BigDecimal pnl,           // sum of pnl from settlement cells
```

Full record: `periodStart, periodEnd, granularity, deliveryPointId, portfolioId, isPeak, netMw, netMwh, settledValue, marketValue, pnl, forwardMarkValue, currency, calendarVersion, versionHash`

### 6.4 RollupMaterializationService

**New:** `pv-domain/.../service/RollupMaterializationService.java`

Aggregates settlement cells (S5a) into rollup cells (S7) per `(deliveryPoint, portfolio) × period` at configurable granularity (HOURLY, DAILY, MONTHLY).

**`materialize(tenantId, rangeStart, rangeEnd, granularity)`:**
1. Load all positions in delivery range via `PositionLedgerRepository`
2. Load all settlement cells per position via `SettlementCellRepository`
3. Group cells by rollup key: `(deliveryPointId, portfolioId, periodStart, periodEnd)`
4. Aggregate per group:
   - `netMw` = time-weighted average (FR-035: `Σ(MW × minutes) / Σ(minutes)`)
   - `netMwh` = sum (FR-035: MWh sums on roll-up)
   - `settledValue` = sum of `amount`
   - `marketValue` = sum of `marketAmount`
   - `pnl` = sum of `pnl`
5. Persist via `RollupRepository.saveAll()` (upsert)

**`materializeForPosition(tenantId, positionId, rangeStart, rangeEnd)`:**
Incremental variant — triggered by `SettlementPublishedConsumer` on each `SettlementComputed` event.

### 6.5 PositionMonthSummary — Query-Time Aggregation

**New:** `pv-domain/.../model/PositionMonthSummary.java`

```java
public record PositionMonthSummary(
    UUID positionId,
    String tenantId,
    String tradeId,
    String tradeLegId,
    YearMonth deliveryMonth,
    BigDecimal totalMwh,        // sum of volumeMwh
    BigDecimal avgMw,           // time-weighted average of volumeMw
    BigDecimal totalAmount,     // sum of amount (trade value)
    BigDecimal totalMarketAmount, // sum of marketAmount
    BigDecimal totalPnl,        // sum of pnl
    BigDecimal avgPrice,        // volume-weighted: totalAmount / totalMwh
    BigDecimal avgMarketPrice,  // volume-weighted: totalMarketAmount / totalMwh
    String currency,
    int cellCount
) {}
```

Computed on read via SQL GROUP BY on `valuation.settlement_cell` joined with `position.position_ledger_entry`:

```sql
SELECT sc.position_id, ple.trade_id, ple.trade_leg_id,
       date_trunc('month', sc.interval_start AT TIME ZONE 'UTC') AS delivery_month,
       SUM(sc.volume_mwh) AS total_mwh,
       SUM(sc.volume_mw * EXTRACT(EPOCH FROM (sc.interval_end - sc.interval_start)) / 60.0)
         / NULLIF(SUM(EXTRACT(EPOCH FROM (sc.interval_end - sc.interval_start)) / 60.0), 0) AS avg_mw,
       SUM(sc.amount) AS total_amount,
       SUM(sc.market_amount) AS total_market_amount,
       SUM(sc.pnl) AS total_pnl,
       sc.currency, COUNT(*) AS cell_count
FROM valuation.settlement_cell sc
JOIN position.position_ledger_entry ple ON ple.entry_uuid = sc.position_id
WHERE sc.tenant_id = :tenantId ...
GROUP BY sc.position_id, ple.trade_id, ple.trade_leg_id, delivery_month, sc.currency
```

### 6.6 REST Endpoints

| Endpoint | Method | Parameters | Response |
|----------|--------|------------|----------|
| `/api/positions/summary` | GET | `tenantId, rangeStart, rangeEnd, positionId?` | `List<PositionMonthSummaryDto>` |
| `/api/rollups` | GET | `tenantId, deliveryPointId, portfolioId, rangeStart, rangeEnd, granularity?` | `List<RollupCellDto>` |
| `/api/rollups/materialize` | POST | `tenantId, rangeStart, rangeEnd, granularity?` | `"Rollup materialization completed"` |

### 6.7 Kafka Integration

`SettlementPublishedConsumer` updated to include `RollupMaterializationService`:
- On `SettlementComputed` event: calls `materializeForPosition()` for incremental rollup update
- Then calls `rollupRepo.refresh()` for materialized view refresh (existing behavior)

### 6.8 Schema — Rollup Table Extension

```sql
ALTER TABLE volume_series.rollup_cell
  ADD COLUMN IF NOT EXISTS market_value NUMERIC(18, 4) DEFAULT 0,
  ADD COLUMN IF NOT EXISTS pnl NUMERIC(18, 4) DEFAULT 0;
```

### 6.9 Tests

| Test | Cases |
|------|-------|
| `RollupMaterializationServiceTest` (4 tests) | Aggregation math (TWA + sum), no positions = no-op, no cells = no-op, null market values |
| `PositionMonthSummaryTest` (2 tests) | Record field retention, nullable market fields |

### 6.10 Files

| Module | File | Action |
|--------|------|--------|
| pv-domain | `model/PositionMonthSummary.java` | **New** — query-time aggregation record |
| pv-domain | `service/RollupMaterializationService.java` | **New** — S7 materialization from settlement cells |
| pv-domain | `port/repository/RollupCell.java` | Modified — added `marketValue`, `pnl` fields |
| pv-domain | `port/repository/RollupRepository.java` | Modified — added `saveAll()` method |
| pv-domain | `port/repository/SettlementCellRepository.java` | Modified — added `findMonthlySummary()`, `findMonthlySummaryByPosition()` |
| pv-persistence | `adapter/JpaRollupRepository.java` | Modified — `saveAll()` with upsert, updated column mapping |
| pv-persistence | `adapter/JpaSettlementCellRepository.java` | Modified — native SQL summary queries |
| pv-kafka | `SettlementPublishedConsumer.java` | Modified — added `RollupMaterializationService` dep |
| pv-app | `dto/PositionMonthSummaryDto.java` | **New** — REST DTO |
| pv-app | `dto/RollupCellDto.java` | **New** — REST DTO |
| pv-app | `controller/RollupController.java` | **New** — GET + POST endpoints |
| pv-app | `controller/PositionController.java` | Modified — added `/summary` endpoint |
| pv-app | `config/DomainServiceConfig.java` | Modified — wired `RollupMaterializationService` bean |
| pv-guice | `DomainModule.java` | Modified — bound `RollupMaterializationService` |

---

## Enhancement 7: Service Layer Abstraction

### 7.1 Problem

Controllers (`PositionController`, `SettlementController`, `MarketDataController`, `VolumeSeriesController`, `RollupController`) directly injected repository port interfaces. This tight coupling prevented replacing the underlying datastore or adding cross-cutting service logic (caching, validation, authorization) without modifying controllers.

### 7.2 Design

Introduced **5 service interfaces** in `pv-domain/port/service/` with **default implementations** in `pv-domain/service/`:

| Interface | Implementation | Repositories Abstracted |
|-----------|---------------|------------------------|
| `PositionQueryService` | `DefaultPositionQueryService` | `PositionLedgerRepository`, `SettlementCellRepository` |
| `SettlementQueryService` | `DefaultSettlementQueryService` | `SettlementCellRepository` |
| `MarketDataService` | `DefaultMarketDataService` | `MarketDataRepository` |
| `VolumeSeriesQueryService` | `DefaultVolumeSeriesQueryService` | `VolumeSeriesRepository` |
| `RollupQueryService` | `DefaultRollupQueryService` | `RollupRepository`, `RollupMaterializationService` |

**Note:** `TradeController` already used service interfaces (`TradeCaptureHandler`, `TradeAmendHandler`, `TradeCancelHandler`) — no change needed. `HealthController` uses infrastructure directly (correct for health checks).

### 7.3 Layer Structure

```
Controller (pv-app)
  └─ depends on ─> Service Interface (pv-domain/port/service/)
                       └─ implemented by ─> Default Service (pv-domain/service/)
                                               └─ delegates to ─> Repository Port (pv-domain/port/repository/)
                                                                     └─ implemented by ─> JPA Adapter (pv-persistence/)
```

### 7.4 Files

| Module | File | Action |
|--------|------|--------|
| pv-domain | `port/service/PositionQueryService.java` | **New** — interface |
| pv-domain | `port/service/SettlementQueryService.java` | **New** — interface |
| pv-domain | `port/service/MarketDataService.java` | **New** — interface |
| pv-domain | `port/service/VolumeSeriesQueryService.java` | **New** — interface |
| pv-domain | `port/service/RollupQueryService.java` | **New** — interface |
| pv-domain | `service/DefaultPositionQueryService.java` | **New** — implementation |
| pv-domain | `service/DefaultSettlementQueryService.java` | **New** — implementation |
| pv-domain | `service/DefaultMarketDataService.java` | **New** — implementation |
| pv-domain | `service/DefaultVolumeSeriesQueryService.java` | **New** — implementation |
| pv-domain | `service/DefaultRollupQueryService.java` | **New** — implementation |
| pv-app | `controller/PositionController.java` | Modified — uses `PositionQueryService` |
| pv-app | `controller/SettlementController.java` | Modified — uses `SettlementQueryService` |
| pv-app | `controller/MarketDataController.java` | Modified — uses `MarketDataService` |
| pv-app | `controller/VolumeSeriesController.java` | Modified — uses `VolumeSeriesQueryService` |
| pv-app | `controller/RollupController.java` | Modified — uses `RollupQueryService` |
| pv-app | `config/DomainServiceConfig.java` | Modified — wired 5 service beans |
| pv-guice | `DomainModule.java` | Modified — bound 5 service interface-to-impl pairs |

---

## Enhancement 8: System Flow Diagrams, Test Trigger APIs & Developer Reference

### 8.0 Event Trigger APIs (for testing)

REST endpoints that publish domain events to the outbox table, exercising the full production flow (outbox → Kafka relay → consumer → revaluation) without requiring direct Kafka access.

**`EventTriggerController`** — `pv-app/.../controller/EventTriggerController.java`

| Method | Endpoint | Event Published | Kafka Topic |
|--------|----------|-----------------|-------------|
| POST | `/api/events/market-data-updated` | `MarketDataUpdated` | `posval.MarketDataUpdated` |
| POST | `/api/events/volume-superseded` | `VolumeSuperseded` | `posval.VolumeSuperseded` |

Both endpoints accept a JSON body, construct the domain event, and publish via `DomainEventPublisher` (outbox pattern). The `OutboxRelayProducer` picks up the event and relays to the corresponding Kafka topic. From there, the standard consumer pipeline executes identically to production.

**DTOs:**

- `MarketDataUpdatedRequest` — maps to `MarketDataUpdated` event. Fields: `tenantId`, `dataType` (enum string), `series`, `affectedRangeStart`/`End` (nullable ISO-8601), `newVersionId`.
- `VolumeSupersededRequest` — maps to `VolumeSuperseded` event. Fields: `seriesKey`, `layer`, `seriesType` (nullable), `affectedRangeStart`/`End` (ISO-8601 zoned), `deliveryTimezone`, `oldVersionId` (nullable), `newVersionId`, `qualityState`.

### 8.1 Flow 1: Trade Capture → Settlement Materialization

```
[External System]
    │
    ▼ POST /api/trades/capture
┌─────────────────┐
│ TradeController  │
│  (pv-app)       │
└────────┬────────┘
         │ tradeCaptureHandler.handle(cmd)
         ▼
┌──────────────────────────┐
│ DefaultTradeCaptureHandler│   Decomposes delivery period into monthly blocks
│  (pv-domain)              │   Creates PositionLedgerEntry per month
└────────┬─────────────────┘
         │ eventPublisher.publish(PositionEntryCaptured)
         ▼
┌────────────────────┐
│ outbox_event table │   Written by OutboxDomainEventPublisher
└────────┬───────────┘
         │ OutboxRelayProducer.relay()
         ▼
┌──────────────────────────────────────────┐
│ Kafka: posval.PositionEntryCaptured      │
└────────┬─────────────────────────────────┘
         │ TradeCapturedKafkaListener
         ▼
┌──────────────────────────┐
│ TradeCapturedConsumer    │   Idempotent: checks existsByPositionId
│  (pv-kafka)              │
└────────┬─────────────────┘
         │ settlementJob.execute(entry, deliveryRange)
         ▼
┌───────────────────────────────┐
│ SettlementMaterializationJob  │   resolveVolume → evaluatePrice → buildResult → flush
│  (pv-domain)                  │   Creates 15-min SettlementCells with price, amount,
└────────┬──────────────────────┘   marketPrice, marketAmount, pnl
         │ eventPublisher.publish(SettlementComputed)
         ▼
┌──────────────────────────────────────────┐
│ Kafka: posval.SettlementComputed         │
└────────┬─────────────────────────────────┘
         │ SettlementPublishedConsumer
         ▼
┌────────────────────────────────┐
│ RollupMaterializationService   │   Aggregates cells → RollupCell (S7)
│  + RollupRepository.refresh()  │
└────────────────────────────────┘
```

**Sample payload — `POST /api/trades/capture`:**

```json
{
  "tenantId": "TN_0042",
  "tradeId": "T-7788",
  "tradeLegId": "LEG-1",
  "tradeVersion": 1,
  "deliveryPeriodStart": "2025-01-01T00:00:00+01:00[Europe/Berlin]",
  "deliveryPeriodEnd": "2026-01-01T00:00:00+01:00[Europe/Berlin]",
  "deliveryTimezone": "Europe/Berlin",
  "quantity": "50.0",
  "volumeUnit": "MW_CAPACITY",
  "priceExpressionId": "00000000-0000-0000-0000-000000000001",
  "marketPriceExpressionId": "00000000-0000-0000-0000-000000000002",
  "portfolioId": "PF-WIND-EU",
  "deliveryPointId": "DE_LU",
  "originType": "BILATERAL_TRADE",
  "volumeSeriesKey": "FORECAST-WIND-DE-LU",
  "multiplier": "0.35"
}
```

**Sample Kafka event — `posval.PositionEntryCaptured`:**

```json
{
  "tenantId": "TN_0042",
  "positionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "eventTime": "2025-01-15T10:30:00Z"
}
```

### 8.2 Flow 2: Market Data Update → Settlement Revaluation

```
[Test via REST API]                         [Production — internal]
    │                                           │
    ▼ POST /api/events/market-data-updated      ▼ Domain operation publishes event
┌──────────────────────┐                   ┌─────────────────────┐
│ EventTriggerController│                  │ DomainEventPublisher │
│  (pv-app)             │                  │  (called internally) │
└────────┬─────────────┘                   └────────┬────────────┘
         │                                          │
         └──────────────┬───────────────────────────┘
                        ▼
                 ┌─────────────────┐
                 │ outbox_event    │   Written by OutboxDomainEventPublisher
                 └────────┬────────┘
                          │ OutboxRelayProducer.relay()
                          ▼
               ┌──────────────────────────────────────────┐
               │ Kafka: posval.MarketDataUpdated          │
               └────────┬─────────────────────────────────┘
                        │ MarketDataUpdatedKafkaListener
                        ▼
               ┌───────────────────────────┐
               │ MarketDataUpdatedConsumer │   1. Invalidate cache
               │  (pv-kafka)               │   2. S8 dependency index lookup:
               │                            │      series + range → affected position IDs
               │                            │      (FR-103 blast-radius optimization)
               └────────┬──────────────────┘
                        │ eventPublisher.publish(SettlementRevaluationRequested) per position
                        ▼
               ┌──────────────────────────────────────────────────┐
               │ Kafka: posval.SettlementRevaluationRequested     │
               └────────┬─────────────────────────────────────────┘
                        │ SettlementRevaluationKafkaListener
                        ▼
               ┌──────────────────────────────────┐
               │ SettlementRevaluationConsumer    │   Loads position by ID
               │  (pv-kafka)                      │
               └────────┬─────────────────────────┘
                        │ revaluationService.revalue(position, start, end)
                        ▼
               ┌───────────────────────────────────┐
               │ SettlementRevaluationService      │   1. Clamp to delivery boundaries
               │  (pv-domain)                      │   2. Resolve volume for sub-range
               │                                    │   3. Evaluate trade + market price
               │                                    │   4. DELETE old cells in [start, end)
               │                                    │   5. INSERT new cells
               │                                    │   6. Publish SettlementComputed
               └────────────────────────────────────┘
```

**Test trigger — `POST /api/events/market-data-updated`:**

```json
{
  "tenantId": "TN_0042",
  "dataType": "FIXING",
  "series": "EPEX_DA15",
  "affectedRangeStart": "2025-03-01T00:00:00Z",
  "affectedRangeEnd": "2025-04-01T00:00:00Z",
  "newVersionId": 5
}
```

**Response:**

```json
{
  "data": "MarketDataUpdated event published to outbox",
  "message": "Event will be relayed to posval.MarketDataUpdated topic",
  "timestamp": "2025-03-02T08:15:00Z"
}
```

**Resulting Kafka event — `posval.MarketDataUpdated`:**

```json
{
  "tenantId": "TN_0042",
  "dataType": "FIXING",
  "series": "EPEX_DA15",
  "affectedRangeStart": "2025-03-01T00:00:00Z",
  "affectedRangeEnd": "2025-04-01T00:00:00Z",
  "newVersionId": 5,
  "eventTime": "2025-03-02T08:15:00Z"
}
```

**Resulting Kafka event — `posval.SettlementRevaluationRequested`:**

```json
{
  "tenantId": "TN_0042",
  "positionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "intervalStart": "2025-03-01T00:00:00Z",
  "intervalEnd": "2025-04-01T00:00:00Z",
  "triggerType": "MARKET_DATA",
  "eventTime": "2025-03-02T08:15:00Z"
}
```

### 8.3 Flow 3: Volume Superseded → Settlement Revaluation

```
[Test via REST API]                              [Production — internal]
    │                                                │
    ▼ POST /api/events/volume-superseded             ▼ Domain operation publishes event
┌──────────────────────┐                        ┌─────────────────────┐
│ EventTriggerController│                       │ DomainEventPublisher │
│  (pv-app)             │                       │  (called internally) │
└────────┬─────────────┘                        └────────┬────────────┘
         │                                               │
         └──────────────┬────────────────────────────────┘
                        ▼
                 ┌─────────────────┐
                 │ outbox_event    │   Written by OutboxDomainEventPublisher
                 └────────┬────────┘
                          │ OutboxRelayProducer.relay()
                          ▼
               ┌──────────────────────────────────────────┐
               │ Kafka: posval.VolumeSuperseded           │
               └────────┬─────────────────────────────────┘
                        │ VolumeSupersededKafkaListener
                        ▼
               ┌──────────────────────────┐
               │ VolumeSupersededConsumer │   1. Invalidate volume cache
               │  (pv-kafka)              │   2. Find positions by seriesKey + delivery overlap
               └────────┬─────────────────┘
                        │ eventPublisher.publish(SettlementRevaluationRequested) per position
                        ▼
               ┌──────────────────────────────────────────────────┐
               │ Kafka: posval.SettlementRevaluationRequested     │
               │  (same flow as 8.2 above)                        │
               └──────────────────────────────────────────────────┘
```

**Test trigger — `POST /api/events/volume-superseded`:**

```json
{
  "seriesKey": "FORECAST-WIND-DE-LU",
  "layer": "FORECAST",
  "seriesType": "FORECAST_SERIES",
  "affectedRangeStart": "2025-03-01T00:00:00+01:00",
  "affectedRangeEnd": "2025-04-01T00:00:00+02:00",
  "deliveryTimezone": "Europe/Berlin",
  "oldVersionId": 3,
  "newVersionId": 4,
  "qualityState": "VALIDATED"
}
```

**Response:**

```json
{
  "data": "VolumeSuperseded event published to outbox",
  "message": "Event will be relayed to posval.VolumeSuperseded topic",
  "timestamp": "2025-03-15T06:00:00Z"
}
```

**Resulting Kafka event — `posval.VolumeSuperseded`:**

```json
{
  "seriesKey": "FORECAST-WIND-DE-LU",
  "layer": "FORECAST",
  "seriesType": "FORECAST_SERIES",
  "affectedRange": {
    "start": "2025-03-01T00:00:00+01:00[Europe/Berlin]",
    "end": "2025-04-01T00:00:00+02:00[Europe/Berlin]"
  },
  "oldVersionId": 3,
  "newVersionId": 4,
  "qualityState": "VALIDATED",
  "eventTime": "2025-03-15T06:00:00Z"
}
```

### 8.4 Flow 4: Position Summary Query (Read Path)

```
[Client/UI]
    │
    ▼ GET /api/positions/summary?tenantId=TN_0042&rangeStart=2025-01-01T00:00:00Z&rangeEnd=2026-01-01T00:00:00Z
┌──────────────────┐
│ PositionController│
│  (pv-app)         │
└────────┬─────────┘
         │ positionService.monthlySummary(tenantId, rangeStart, rangeEnd)
         ▼
┌──────────────────────────────┐
│ DefaultPositionQueryService  │
│  (pv-domain)                 │
└────────┬─────────────────────┘
         │ cellRepo.findMonthlySummary(tenantId, rangeStart, rangeEnd)
         ▼
┌──────────────────────────────┐
│ JpaSettlementCellRepository  │   SQL GROUP BY: TWA(MW), SUM(MWh, amount, PnL)
│  (pv-persistence)            │
└────────┬─────────────────────┘
         │
         ▼ Returns List<PositionMonthSummary>
```

**Sample response:**

```json
{
  "data": [
    {
      "positionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "tenantId": "TN_0042",
      "tradeId": "T-7788",
      "tradeLegId": "LEG-1",
      "deliveryMonth": "2025-03",
      "totalMwh": 2880.00,
      "avgMw": 50.00,
      "totalAmount": 244800.00,
      "totalMarketAmount": 80640.00,
      "totalPnl": -164160.00,
      "avgPrice": 85.00000000,
      "avgMarketPrice": 28.00000000,
      "currency": "EUR",
      "cellCount": 2976
    }
  ],
  "message": "OK",
  "timestamp": "2025-03-15T10:00:00Z"
}
```

### 8.5 Flow 5: Rollup Query and Materialization

```
[Client/UI — Trigger Materialization]
    │
    ▼ POST /api/rollups/materialize?tenantId=TN_0042&rangeStart=2025-01-01T00:00:00Z&rangeEnd=2026-01-01T00:00:00Z&granularity=MONTHLY
┌──────────────────┐
│ RollupController  │
│  (pv-app)         │
└────────┬─────────┘
         │ rollupService.materialize(tenantId, rangeStart, rangeEnd, MONTHLY)
         ▼
┌──────────────────────────────────┐
│ RollupMaterializationService     │   1. Load positions in range
│  (pv-domain)                     │   2. Load settlement cells per position
│                                   │   3. Group by (deliveryPoint, portfolio, month)
│                                   │   4. Aggregate: TWA(MW), SUM(MWh, amount, PnL)
│                                   │   5. Upsert to rollup_cell table
└──────────────────────────────────┘

[Client/UI — Query Rollups]
    │
    ▼ GET /api/rollups?tenantId=TN_0042&deliveryPointId=DE_LU&portfolioId=PF-WIND-EU&rangeStart=2025-01-01T00:00:00Z&rangeEnd=2026-01-01T00:00:00Z&granularity=MONTHLY
```

**Sample response — `GET /api/rollups`:**

```json
{
  "data": [
    {
      "periodStart": "2025-03-01T00:00:00Z",
      "periodEnd": "2025-04-01T00:00:00Z",
      "granularity": "MONTHLY",
      "deliveryPointId": "DE_LU",
      "portfolioId": "PF-WIND-EU",
      "isPeak": false,
      "netMw": 49.00000000,
      "netMwh": 36456.00000000,
      "settledValue": 3098760.0000,
      "marketValue": 1020768.0000,
      "pnl": -2077992.0000,
      "forwardMarkValue": 0,
      "currency": "EUR",
      "calendarVersion": null,
      "versionHash": "a3f2c1b0"
    }
  ],
  "message": "OK",
  "timestamp": "2025-03-15T10:00:00Z"
}
```

### 8.6 Flow 6: Settlement Cell Query

```
GET /api/settlements?tenantId=TN_0042&positionId=a1b2c3d4-...&rangeStart=2025-03-01T00:00:00Z&rangeEnd=2025-03-01T01:00:00Z
```

**Sample response:**

```json
{
  "data": [
    {
      "cellId": "b1c2d3e4-f5g6-7890-abcd-ef1234567890",
      "tenantId": "TN_0042",
      "positionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "intervalStart": "2025-03-01T00:00:00Z",
      "intervalEnd": "2025-03-01T00:15:00Z",
      "valuationType": "SETTLEMENT",
      "cellStatus": "PROVISIONAL",
      "price": 85.00000000,
      "volumeMw": 50.00000000,
      "volumeMwh": 12.50000000,
      "amount": 1062.5000,
      "marketPrice": 28.06000000,
      "marketAmount": 350.7500,
      "pnl": -711.7500,
      "currency": "EUR",
      "activeLeaves": ["EPEX_DA15", "CPI_DE"],
      "computedAt": "2025-03-02T08:15:00Z"
    }
  ],
  "message": "OK",
  "timestamp": "2025-03-15T10:00:00Z"
}
```

### 8.7 Kafka Topic Reference

| Topic | Event Type | Producer | Consumer | Partition Key |
|-------|-----------|----------|----------|---------------|
| `posval.PositionEntryCaptured` | Position entry created | `OutboxRelayProducer` | `TradeCapturedKafkaListener` | `tenantId` |
| `posval.MarketDataUpdated` | Market data changed | `OutboxRelayProducer` | `MarketDataUpdatedKafkaListener` | `tenantId` |
| `posval.VolumeSuperseded` | Volume series updated | `OutboxRelayProducer` | `VolumeSupersededKafkaListener` | `tenantId` |
| `posval.SettlementRevaluationRequested` | Revaluation trigger | `OutboxRelayProducer` | `SettlementRevaluationKafkaListener` | `positionId` |
| `posval.SettlementComputed` | Settlement cell created | `OutboxRelayProducer` | `SettlementPublishedConsumer` | `positionId` |
| `posval.CurveTick` | Forward curve updated | External | `CurveTickConsumer` | `series` |

### 8.8 REST API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/trades/capture` | Capture a new trade → creates position entries |
| POST | `/api/trades/amend` | Amend an existing trade → supersedes position entries |
| POST | `/api/trades/cancel` | Cancel a trade → creates cancellation entries |
| GET | `/api/positions` | Current position entries for a trade-leg |
| GET | `/api/positions/as-of` | Bitemporal as-of position reconstruction |
| GET | `/api/positions/by-range` | All positions within a delivery range |
| GET | `/api/positions/summary` | Monthly aggregation of settlement cells per position |
| GET | `/api/settlements` | Settlement cells for a position within a range |
| GET | `/api/rollups` | Materialized rollup cells by delivery point + portfolio |
| POST | `/api/rollups/materialize` | Trigger rollup materialization from settlement cells |
| GET | `/api/market-data/fixings` | Query fixing prices |
| POST | `/api/market-data/fixings` | Store fixing prices |
| GET | `/api/market-data/forward-curves` | Query forward curve pillars |
| POST | `/api/market-data/forward-curves` | Store forward curve pillars |
| GET | `/api/market-data/indices` | Query index values |
| POST | `/api/market-data/indices` | Store index values |
| GET | `/api/market-data/fx-rates` | Query FX rates |
| POST | `/api/market-data/fx-rates` | Store FX rates |
| GET | `/api/volume-series` | List volume series for a tenant |
| GET | `/api/volume-series/{id}` | Get volume series by ID |
| POST | `/api/events/market-data-updated` | Trigger MarketDataUpdated via outbox → Kafka (test) |
| POST | `/api/events/volume-superseded` | Trigger VolumeSuperseded via outbox → Kafka (test) |
| GET | `/api/health` | Health check (DB + Redis) |
| GET | `/api/cache/stats` | Redis cache statistics |

---

## Enhancement 9: S8 Dependency-Index Blast-Radius Optimization

### 9.1 Problem

When market data changes (e.g., EPEX DA15 fixings for March 2025), the system needs to find which positions are affected and trigger revaluation. The original `MarketDataUpdatedConsumer` used `PositionLedgerRepository.findAllByDeliveryRange()` — a brute-force scan returning **all** positions in the delivery range, regardless of whether their price expressions reference the changed series.

For a tenant with 200 active trades across 12 delivery months, an EPEX fixing update for March would trigger revaluation for ~200 positions — even if only 15 trades actually use EPEX DA15 in their price expressions. The remaining 185 (priced on NORDPOOL, EEX, or fixed-price) would be revalued unnecessarily.

### 9.2 Design

The S8 dependency index (`valuation.dependency_edge`) already records which market data series each settlement cell depends on (via `activeLeaves` and `inputSeriesKey`). Enhancement 9 leverages this existing infrastructure:

```
MarketDataUpdated(series="EPEX_DA15", range=[Mar-01, Apr-01))
  │
  ▼ DependencyIndex.findAffectedPositionIds(tenantId, "EPEX_DA15", Mar-01, Apr-01)
  │   SQL: SELECT DISTINCT sc.position_id
  │        FROM dependency_edge de
  │        JOIN settlement_cell sc ON sc.cell_id = de.cell_id
  │        WHERE de.input_series_key = 'EPEX_DA15'
  │          AND de.affected_range overlaps [Mar-01, Apr-01)
  │          AND de.pruned_at IS NULL
  │
  ▼ Returns only position IDs whose price expressions reference EPEX_DA15
  │   (e.g., 15 out of 200 positions)
  │
  ▼ Publish SettlementRevaluationRequested per distinct position
```

**Contrast with VolumeSuperseded:** Volume events don't need the dependency index because `PositionLedgerEntry.volumeSeriesKey` provides a direct FK-like lookup. Market data has no such direct link — the connection is `position → priceExpression → expression tree → leaf nodes → market data series`, which is exactly what `dependency_edge` materializes.

### 9.3 DependencyIndex Port Extension

New default method on `DependencyIndex`:

```java
/**
 * Find distinct position IDs affected by an input series change.
 * Joins dependency_edge with settlement_cell to resolve cell_id → position_id.
 * FR-103: S8 blast-radius optimization.
 */
List<UUID> findAffectedPositionIds(String tenantId,
                                    String inputSeriesKey,
                                    Instant rangeStart,
                                    Instant rangeEnd);
```

### 9.4 JPA Implementation

`JpaDependencyIndex.findAffectedPositionIds()`:

```sql
SELECT DISTINCT sc.position_id
FROM valuation.dependency_edge de
JOIN valuation.settlement_cell sc ON sc.cell_id = de.cell_id
WHERE de.tenant_id = :tenantId
  AND de.input_series_key = :inputSeriesKey
  AND de.affected_range_start < :rangeEnd
  AND de.affected_range_end > :rangeStart
  AND de.pruned_at IS NULL
```

Index requirement: `dependency_edge(tenant_id, input_series_key, affected_range_start, affected_range_end)` — already covered by existing indexes on the table.

### 9.5 Consumer Refactoring

`MarketDataUpdatedConsumer` constructor changed from:

```java
// OLD: brute-force position scan
MarketDataUpdatedConsumer(MarketDataCache cache,
                           PositionLedgerRepository ledgerRepo,
                           DomainEventPublisher eventPublisher)
```

to:

```java
// NEW: S8 dependency index lookup
MarketDataUpdatedConsumer(MarketDataCache cache,
                           DependencyIndex dependencyIndex,
                           DomainEventPublisher eventPublisher)
```

The `PositionLedgerRepository` dependency is removed entirely — position IDs come from the dependency index join, not from the position ledger.

### 9.6 Spring Wiring

| Bean | Config | Purpose |
|------|--------|---------|
| `JpaDependencyIndex` | `PersistenceConfig` | S8 adapter bean (was missing from Spring config) |
| `MarketDataUpdatedConsumer` | `KafkaConfig` | Now takes `DependencyIndex` instead of `PositionLedgerRepository` |
| `MarketDataUpdatedKafkaListener` | `pv-app/kafka/` (new) | Spring `@KafkaListener` for `posval.MarketDataUpdated` topic |
| `marketDataUpdatedConsumerFactory` | `KafkaConfig` | Typed `ConsumerFactory<String, MarketDataUpdated>` |
| `marketDataUpdatedListenerFactory` | `KafkaConfig` | Container factory with DLQ + exponential backoff |

### 9.7 Tests

| Test | Cases |
|------|-------|
| `MarketDataUpdatedConsumerTest` (7 tests) | Cache invalidation (full + range), idempotent reprocessing, S8 index returns 2 positions → 2 reval events, no affected positions → no events, full-series → no reval, correct series key passed to index |

### 9.8 Files

| Module | File | Action |
|--------|------|--------|
| pv-domain | `port/repository/DependencyIndex.java` | Modified — added `findAffectedPositionIds()` |
| pv-persistence | `adapter/JpaDependencyIndex.java` | Modified — implemented join query |
| pv-kafka | `MarketDataUpdatedConsumer.java` | Modified — `DependencyIndex` replaces `PositionLedgerRepository` |
| pv-app | `kafka/MarketDataUpdatedKafkaListener.java` | **New** — Spring Kafka listener |
| pv-app | `config/KafkaConfig.java` | Modified — consumer factory, listener factory, consumer bean |
| pv-app | `config/PersistenceConfig.java` | Modified — `JpaDependencyIndex` bean |

---

## Updated Combined Impact Summary

### Test Coverage (Final)

- **pv-domain**: 247 tests
- **pv-persistence**: 13 tests
- **pv-kafka**: 17 tests (including `MarketDataUpdatedConsumerTest` (7))
- **pv-app**: 31 tests
- **pv-integration-tests**: 5 tests
- **pv-redis**: 5 tests
- **Total**: 318 tests, all passing

### All New Files (Enhancements 1–9)

| Enh. | File | Purpose |
|------|------|---------|
| 1 | `PositionEntryCaptured.java` | Per-entry event replacing `PositionCaptured` |
| 2 | `InstrumentedMarketDataPort.java` | ThreadLocal timing decorator |
| 2 | `PriceCalcTimingStats.java` | Mutable accumulator: lookup count + nanos |
| 3 | `RuleEngineConfig.java` | Conditional Spring config for MVEL rule engine |
| 3 | `RuleEngineBasedEvaluator.java` | MVEL-backed `PriceEvaluator` |
| 3 | `PriceRuleDefinition.java` | Formula + constants + series bindings record |
| 3 | `MvelPrecisionHelper.java` | Rounding bridge for MVEL context |
| 4 | *(no new files — existing models modified)* | |
| 5 | `SettlementRevaluationRequested.java` | Internal revaluation trigger event |
| 5 | `SettlementRevaluationService.java` | Core revaluation: clamp → resolve → evaluate → delete → save |
| 5 | `SettlementRevaluationConsumer.java` | Kafka consumer |
| 5 | `SettlementRevaluationKafkaListener.java` | Spring Kafka listener |
| 6 | `PositionMonthSummary.java` | Query-time aggregation record |
| 6 | `RollupMaterializationService.java` | S7 materialization from settlement cells |
| 6 | `PositionMonthSummaryDto.java` | REST DTO |
| 6 | `RollupCellDto.java` | REST DTO |
| 6 | `RollupController.java` | REST controller for rollups |
| 7 | `PositionQueryService.java` | Service interface |
| 7 | `SettlementQueryService.java` | Service interface |
| 7 | `MarketDataService.java` | Service interface |
| 7 | `VolumeSeriesQueryService.java` | Service interface |
| 7 | `RollupQueryService.java` | Service interface |
| 7 | `DefaultPositionQueryService.java` | Default implementation |
| 7 | `DefaultSettlementQueryService.java` | Default implementation |
| 7 | `DefaultMarketDataService.java` | Default implementation |
| 7 | `DefaultVolumeSeriesQueryService.java` | Default implementation |
| 7 | `DefaultRollupQueryService.java` | Default implementation |
| 8 | `EventTriggerController.java` | REST API for triggering events via outbox → Kafka |
| 8 | `MarketDataUpdatedRequest.java` | DTO for market data update trigger |
| 8 | `VolumeSupersededRequest.java` | DTO for volume superseded trigger |
| 9 | `MarketDataUpdatedKafkaListener.java` | Spring Kafka listener for market data events |
