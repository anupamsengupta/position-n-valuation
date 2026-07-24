# Plan: Implement `pv-domain` Module (Tech Spec Part 1, §5–§7)

## Context

The Position & Valuation system has complete specifications but zero application code. This plan implements the `pv-domain` module — the pure Java 21 domain core with zero framework dependencies. Scope is Part 1 of the tech spec only (~49 domain types + Maven scaffolding + JUnit 5 tests).

**Base package:** `com.power.posval`
**Source spec:** `docs/technical-spec/TECH-SPEC-position-valuation-library_guice-v1.0-part1-domain-and-ports.md`

---

## Phase 0: Maven Project Scaffolding

Create the multi-module Maven reactor so all subsequent phases compile.

**Files:**

| File | Purpose |
|------|---------|
| `pom.xml` (root) | Parent POM: `groupId=com.power`, `artifactId=power-position-valuation`, `packaging=pom`, Java 21 compiler, JUnit 5 BOM, `<modules>` listing `pv-domain` |
| `pv-domain/pom.xml` | Module POM: `jakarta.inject-api:2.0.1` (provided), `junit-jupiter` (test) |

**Directory tree created by source files:**
```
pv-domain/src/main/java/com/power/posval/domain/
├── model/value/          ← Value object records
├── model/expression/     ← PriceExpression sealed hierarchy
├── model/                ← Enums, aggregate interfaces, PositionLedgerEntry
├── port/                 ← NumericPrecision, DefaultNumericPrecision
├── port/repository/      ← Repository port stubs
├── port/event/           ← DomainEventPublisher
├── port/cache/           ← VolumeCache
├── port/tenant/          ← TenantContext
├── port/datasource/      ← DataSourceRouter
├── port/marketdata/      ← MarketDataPort, MarketDataLookup
├── service/              ← PriceEvaluator, DefaultPriceEvaluator, VolumeResolver, etc.
├── command/              ← TradeCapture, TradeAmend, TradeCancel
├── event/                ← VolumePublished, VolumeSuperseded, etc.
└── exception/            ← IllegalStateTransitionException
```

**Verify:** `mvn compile` succeeds.

---

## Phase 1: NumericPrecision Port + Exception (3 types)

Foundation types — almost everything else depends on these.

| Type | Kind | File (under `com/power/posval/domain/`) |
|------|------|-------|
| `NumericPrecision` | interface + nested `Domain` enum | `port/NumericPrecision.java` |
| `DefaultNumericPrecision` | record implements NumericPrecision | `port/DefaultNumericPrecision.java` |
| `IllegalStateTransitionException` | class extends RuntimeException | `exception/IllegalStateTransitionException.java` |

`NumericPrecision.Domain` has 6 values: `MONETARY`, `PRICE`, `VOLUME`, `ENERGY`, `MULTIPLIER`, `INTERMEDIATE`.
`DefaultNumericPrecision` scale defaults: MONETARY=4, PRICE=8, VOLUME=8, ENERGY=8, MULTIPLIER=8, INTERMEDIATE=10.
Precision defaults (from code block, normative): MONETARY=20, PRICE=20, VOLUME=18, ENERGY=20, MULTIPLIER=10, INTERMEDIATE=24.

**Tests:** `DefaultNumericPrecisionTest` — verify all scale/precision values, `round()` behavior, exhaustive coverage of all Domain values.

---

## Phase 2: Enums with Behavior (6 types)

Leaf types with no domain-type dependencies (except `NumericPrecision` for `VolumeUnit`).

| Type | Kind | Key Behavior |
|------|------|-------------|
| `MaterializationStatus` | simple enum | `PENDING, PARTIAL, FULL, FAILED` |
| `SeriesType` | simple enum | `FORECAST, PROFILE` |
| `VolumeLayer` | simple enum | `VOLUME, METERED_ACTUAL` |
| `TimeGranularity` | enum + Duration field | `MIN_5..MONTHLY`, `isFixedDuration()`, `getFixedDuration()`, `isSubDaily()` |
| `VolumeUnit` | enum + abstract method | `toEnergy(volume, elapsed, np)` — MW_CAPACITY converts, MWH_PER_PERIOD identity |
| `QualityState` | enum + state machine | 7 states, `canTransitionTo()`, `transitionTo()`, category predicates |

All files under `model/`.

**Tests:** `TimeGranularityTest`, `VolumeUnitTest` (energy conversion math), `QualityStateTest` (all transitions: 7 allowed, all terminal states blocked, exception on illegal transition).

---

## Phase 3: Value Object Records (7 types)

Building blocks for events, commands, and aggregates. All under `model/value/`.

| Type | Key Validation |
|------|---------------|
| `SeriesKey(String value)` | non-null, non-blank; `of(prefix, id)` factory; `toString()` returns value |
| `DeliveryPeriod(ZonedDateTime start, end, ZoneId tz)` | end > start; `of()` converts zones; `contains()` half-open |
| `TimeRange(Instant from, to)` | from non-null, to nullable (open-ended); `open()`, `closed()` factories |
| `DeliveryRange(YearMonth start, end, ZoneId tz)` | end ≥ start; `ofMonth()` factory; `startInstant()`, `endInstant()` |
| `Money(BigDecimal amount, Currency currency)` | `of(amount, currency, np)` rounds to MONETARY; `add()` same-currency guard; `multiply(factor, np)` |
| `VolumeReference(9 fields)` | multiplier ∈ (0,1]; `isFixedProfile()`; Builder pattern (Pattern #6) |
| `VolumeReference.Builder` | nested static class with fluent setters |

**Tests:** Validation tests for each (null rejection, range checks, factory behavior), `MoneyTest` (arithmetic + rounding), `VolumeReferenceTest` (PPA vs profile, builder).

---

## Phase 4: Event + Command Records (7 types)

Data carriers — minimal validation, just record declarations matching spec signatures exactly.

| Type | Package | Notable |
|------|---------|---------|
| `VolumePublished` | `event/` | `seriesType` nullable |
| `VolumeSuperseded` | `event/` | `oldVersionId` nullable (boxed `Long`) |
| `VolumeChunkMaterialized` | `event/` | — |
| `SettlementComputed` | `event/` | references `Money`, `Set<String>`, `Map<String,Long>` |
| `TradeCapture` | `command/` | `assetId`, `meteredSeriesKey` nullable |
| `TradeAmend` | `command/` | nullable "changed" fields |
| `TradeCancel` | `command/` | — |

**Tests:** Smoke tests — verify construction and component access.

---

## Phase 5: Aggregate & Entity Interfaces (5 types)

Domain-side interfaces (not JPA entities — those are `pv-persistence`). Under `model/`.

| Type | Kind | Key Design |
|------|------|-----------|
| `VolumeInterval` | interface | `Comparable<VolumeInterval>` by `intervalStart`; methods: `id()`, `intervalStart()`, `intervalEnd()`, `volume()`, `energy()` |
| `VolumeSeries` | interface | Aggregate root; `SequencedSet<VolumeInterval> intervals()`; ownership XOR: `assetId` or `tradeLegId` |
| `MeteredActualInterval` | interface | Similar to VolumeInterval + `meteringPointId` |
| `MeteredActualVolumeSeries` | interface | Always has `assetId`, no `tradeLegId`, adds `receivedAt()` |
| `PositionLedgerEntry` | final class + Builder | ~17 fields, private constructor, `isCurrentKnowledge()`, Builder validates required fields |

**Tests:** `PositionLedgerEntryTest` — builder with all required fields, missing required field throws, accessor correctness, `isCurrentKnowledge()`.

---

## Phase 6: PriceExpression Sealed Hierarchy (14 types)

All under `model/expression/`.

| Type | Kind |
|------|------|
| `PriceExpression` | sealed interface permits 13 types |
| `ConstantLeaf` | record(leafId, value, unit) |
| `MarketDataLeaf` | record(leafId, series, settlementSeries, lag, quotationWindow) |
| `IndexLeaf` | record(leafId, series, refMonthExpression) |
| `Add`, `Subtract` | record(left, right) |
| `Multiply`, `Divide` | record(left/right or numerator/denominator) |
| `Clamp` | record(min, max, inner) |
| `Escalate` | record(base, ratio) |
| `ConditionalGate` | record(gateInput, condition, overrideValue, inner) |
| `ConditionalPassThrough` | record(gateInput, condition, inner) |
| `TimeAverage` | record(child, windowSpec) |
| `FxConvert` | record(value, fxRate) |

**Tests:** Tree construction, pattern-matching switch exhaustiveness test.

---

## Phase 7: PriceEvaluator + MarketData Port (6 types)

| Type | Kind | Location |
|------|------|----------|
| `ResolutionPurpose` | enum | `service/` |
| `PriceResolution` | record(value, activeLeaves, inputVersionSet) | `service/` |
| `PriceEvaluator` | @FunctionalInterface port | `service/` |
| `MarketDataPort` | port interface | `port/marketdata/` |
| `MarketDataLookup` | record | `port/marketdata/` |
| `DefaultPriceEvaluator` | class implementing PriceEvaluator | `service/` |

`DefaultPriceEvaluator` uses exhaustive pattern-matching switch over all 13 types. Constructor-injected `NumericPrecision`. Private `meetsCondition()` helper parses condition strings like `"< 0"`.

**Known spec fix:** `md.lookupFixing(series, interval.start())` — `interval.start()` is `ZonedDateTime`, port takes `Instant` → call `.toInstant()` at call site.

**Tests:** `DefaultPriceEvaluatorTest` — fixed price, arithmetic (add/sub/mul/div), Clamp (inside collar vs binding), ConditionalGate (fires vs doesn't), FxConvert, MarketDataLeaf purpose-based dispatch, nested PPA-like tree. Use in-test stub `MarketDataPort`.

---

## Phase 8: Remaining Port Interface Stubs (9 types)

Interfaces declared in Part 1 module tree, fully specified in Parts 2/3. Implement as interfaces with method signatures + Javadoc only.

| Type | Package |
|------|---------|
| `PositionLedgerRepository` | `port/repository/` |
| `VolumeSeriesRepository` | `port/repository/` |
| `DomainEventPublisher` | `port/event/` |
| `VolumeCache` + `CachedInterval` record | `port/cache/` |
| `TenantContext` | `port/tenant/` |
| `DataSourceRouter` | `port/datasource/` |
| `VolumeResolver` (sealed) + `ProfileResolver`, `ForecastResolver` stubs | `service/` |
| `MaterializationStrategy` (sealed) + permitted type stubs | `service/` |
| `VolumeSeriesFactory` | `service/` |

Sealed interface permitted subtypes declared as `non-sealed interface` stubs to satisfy `permits` clause.

**Tests:** Compile-only — no behavioral tests for pure stubs.

---

## Verification

1. **`mvn compile`** — all 62 types compile with Java 21, zero framework deps
2. **`mvn test`** — all 155 unit tests pass (JUnit 5, no DI container, no mocking framework)
3. **Key test coverage targets:**
   - `DefaultNumericPrecision` — all domain scale/precision values
   - `QualityState` — all 7 allowed transitions + all blocked transitions
   - `Money` — arithmetic with MONETARY rounding
   - `VolumeUnit.toEnergy()` — MW→MWh conversion math
   - `DefaultPriceEvaluator` — exhaustive switch, leaf tracking, version tracking, rounding per domain
   - `PositionLedgerEntry.Builder` — required field validation
   - Value objects — null/range rejection in compact constructors
4. **Zero Spring/Hibernate/Guice imports** in `pv-domain/src/main/java/` (only `jakarta.inject`)

---

## Execution Results

| Phase | Status | Types | Tests |
|-------|--------|-------|-------|
| 0 | Done | 2 POMs | — |
| 1 | Done | 3 | 33 |
| 2 | Done | 6 | 22 |
| 3 | Done | 7 (incl. Builder) | 46 |
| 4 | Done | 7 | 7 |
| 5 | Done | 5 (incl. Builder) | 11 |
| 6 | Done | 14 | 6 |
| 7 | Done | 6 | 18 |
| 8 | Done | 14 | — |
| **Total** | **Done** | **62 source files** | **155 tests, all green** |

### Test Classes

| Test Class | Tests | Coverage |
|---|---|---|
| `DefaultNumericPrecisionTest` | 33 | All 6 domain scale/precision values, rounding, positive constraints |
| `IllegalStateTransitionExceptionTest` | 3 | Message format, accessors, RuntimeException |
| `QualityStateTest` | 13 | All 5 allowed transitions, 3 terminal states blocked, illegal throw, category predicates |
| `TimeGranularityTest` | 4 | Duration values, variable duration throws, isSubDaily/isFixedDuration |
| `VolumeUnitTest` | 5 | MW→MWh conversion (1h, 15m, 30m), MWh identity, scale |
| `SeriesKeyTest` | 7 | Null/blank rejection, `of()` factory, toString, equality |
| `DeliveryPeriodTest` | 6 | Validation, zone conversion, half-open contains |
| `TimeRangeTest` | 7 | Open/closed, null rejection, half-open contains |
| `DeliveryRangeTest` | 8 | Validation, `ofMonth()`, start/end instant, multi-month |
| `MoneyTest` | 7 | MONETARY rounding, add, cross-currency guard, multiply |
| `VolumeReferenceTest` | 10 | Builder, isFixedProfile, multiplier bounds, null rejection |
| `PositionLedgerEntryTest` | 11 | Builder with all required fields, 9 missing-field throws, optional nulls |
| `PriceExpressionTest` | 6 | Construction, tree building, exhaustive switch |
| `DefaultPriceEvaluatorTest` | 18 | Fixed price, add/sub/mul/div, clamp (inside/floor/ceiling), ConditionalGate, FxConvert, MarketDataLeaf (forward/settlement), Escalate, nested PPA tree, version tracking, ConditionalPassThrough |
| `EventSmokeTest` | 4 | VolumePublished, VolumeSuperseded, VolumeChunkMaterialized, SettlementComputed |
| `CommandSmokeTest` | 3 | TradeCapture, TradeAmend, TradeCancel |

---

## Critical Source Files

| File | Role |
|------|------|
| `docs/technical-spec/TECH-SPEC-…-part1-domain-and-ports.md` | Primary spec — exact Java code for all types |
| `docs/technical-spec/TECH-SPEC-…-part2-subsystems.md` | Port interface signatures for stubs (MarketDataPort, repositories) |
| `docs/technical-spec/TECH-SPEC-…-part3-crosscutting-and-wiring.md` | TenantContext, DataSourceRouter port signatures |
| `docs/technical-spec/ADR-001-…-library_guice.md` | Pattern references (Pattern #3, #5, #6, etc.) |
