# Technical Specification — Improvements 2026-08-07

## Overview

Five enhancements implemented on branch `spring-app-3`, covering the settlement valuation subsystem (S5a), Kafka async pipeline, materialization observability, and price evaluation strategy:

1. **Kafka Listener Refactor & Per-Entry Event Model** — Replace daemon-thread poll loop with Spring `@KafkaListener`; change from one event per trade to one per position entry
2. **Materialization Execution Timing** — Thread-safe instrumentation separating volume resolution, market data lookup, expression compute, and flush timings
3. **Strategy Pattern: Rule-Engine-Based Price Evaluator** — MVEL-backed alternative `PriceEvaluator` switchable via `pv.pricing.strategy`
4. **Market Price Expression & PnL** — Per-trade mark-to-market price with PnL at materialization time
5. **Interval-Level Settlement Revaluation** — Event-driven recomputation of settlement cells at sub-month granularity

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

Extended with `PositionLedgerRepository` and `DomainEventPublisher` dependencies. After cache invalidation, when `affectedRangeStart`/`affectedRangeEnd` are non-null:

```java
List<PositionLedgerEntry> affected = ledgerRepo.findAllByDeliveryRange(
    event.tenantId(), event.affectedRangeStart(), event.affectedRangeEnd());
for (PositionLedgerEntry pos : affected) {
    eventPublisher.publish(new SettlementRevaluationRequested(
        pos.tenantId(), pos.id(),
        event.affectedRangeStart(), event.affectedRangeEnd(),
        "MARKET_DATA", event.eventTime()));
}
```

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

### Test Coverage

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
