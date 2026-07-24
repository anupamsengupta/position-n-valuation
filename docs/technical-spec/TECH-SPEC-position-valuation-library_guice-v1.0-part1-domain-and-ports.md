# Technical Specification — Position & Valuation (Library + Guice) v1.0

## Part 1 — Domain Model and Ports (§1–§7)

| Part | File | Sections |
|------|------|----------|
| **Part 1 (this file)** | `TECH-SPEC-…-part1-domain-and-ports.md` | §1–§7 |
| Part 2 | `TECH-SPEC-…-part2-subsystems.md` | §8–§13 |
| Part 3 | `TECH-SPEC-…-part3-crosscutting-and-wiring.md` | §14–§22 |

---

## §1 — Metadata & Status

| Field | Value |
|-------|-------|
| **Status** | Draft |
| **Date** | 2026-07-24 |
| **Deciders** | Architecture team |
| **Variant** | Library + Guice (framework-agnostic core; see ADR-001 `-library_guice`) |
| **Companion Specs** | `functional-spec-position-valuation-v1.0.md`, `VOLUME_SERIES_SPEC-V3_0.md`, `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md`, `ADR-001-IMPLEMENTATION-PATTERN-CATALOG-library_guice.md` |
| **Spring Boot variant** | Not covered here. See `ADR-001-…-springboot.md` for the Spring Boot equivalent. |

---

## §2 — Scope & Non-Scope

### 2.1 In Scope

This specification defines:

1. The **domain model** (`pv-domain`): value objects, aggregates, sealed type hierarchies, port interfaces — all in pure Java 21 with zero framework dependencies.
2. **JPA adapter skeletons** (`pv-persistence`): entity mappings, repository implementations, batch writers, audit listeners.
3. **Kafka adapter contracts** (`pv-kafka`): outbox relay, idempotent consumers, event schemas.
4. **Redis adapter contracts** (`pv-redis`): cache port implementation, key scheme, invalidation.
5. **Guice wiring** (`pv-guice`): module bindings, interceptors, bootstrap.
6. **Transaction strategy**, **testing strategy**, **performance requirements**, and **startup sequence**.

### 2.2 Defers To

| Concern | Owning Document |
|---------|-----------------|
| DDL, partitioning SQL, RLS policies | `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` (V2.0) |
| Grid rendering, UX interactions | UX Spec (future) |
| Trade capture upstream events | Trade service spec (future) |
| Numeric precision DDL (column types) | V2.0 §5.0 — defaults governed by `NumericPrecision` port (§5.0) |
| Aurora cluster topology, cost model | V2.0 §3 |
| Retention policy, partition drop | V2.0 §9 |

---

## §3 — Assumptions & Gaps

### 3.1 Input Documents — All Present

All four input documents were available during authoring:

1. `ADR-001-IMPLEMENTATION-PATTERN-CATALOG-library_guice.md` — 35 patterns, module structure, Guice wiring examples.
2. `functional-spec-position-valuation-v1.0.md` — ~120 FR-nnn rules, D-1..D-12, O-1..O-8.
3. `VOLUME_SERIES_SPEC-V3_0.md` — unified volume model, events, consumption contract.
4. `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` — DDL, indexes, cache, events, SLAs.

### 3.2 Inferences

| Item | Inference | Rationale |
|------|-----------|-----------|
| Guice version | 7.x | Latest stable as of 2026; compatible with Jakarta EE 11 (`jakarta.inject`) |
| `guice-persist` vs explicit `UnitOfWork` | Decided in §17 | Both viable; decision deferred to transaction section |
| `SettlementCell` JPA entity | Inferred from FR-070/071 | Functional spec defines attributes; this spec provides JPA skeleton |
| `MarketDataPort` method signatures | Inferred from FR-048e/060/063 | Functional spec defines contract; this spec codifies Java interface |

---

## §4 — Module Structure

### 4.1 Module Tree

```
power-position-valuation/
├── pv-domain/                        ← Pure Java 21, ZERO framework dependencies
│   ├── model/                        ← Records, sealed types, enums, aggregates
│   │   ├── value/                    ← DeliveryPeriod, SeriesKey, Money, TimeRange, DeliveryRange
│   │   ├── expression/               ← PriceExpression sealed hierarchy (13 types)
│   │   ├── PositionLedgerEntry.java
│   │   ├── VolumeSeries.java
│   │   ├── VolumeInterval.java
│   │   ├── VolumeReference.java
│   │   ├── MeteredActualVolumeSeries.java
│   │   ├── MeteredActualInterval.java
│   │   ├── SettlementCell.java
│   │   ├── StruckMark.java
│   │   ├── QualityState.java
│   │   ├── SeriesType.java
│   │   └── VolumeUnit.java
│   ├── port/
│   │   ├── repository/               ← VolumeSeriesRepository, PositionLedgerRepository, ...
│   │   ├── event/                    ← DomainEventPublisher
│   │   ├── cache/                    ← VolumeCache
│   │   ├── tenant/                   ← TenantContext
│   │   ├── datasource/              ← DataSourceRouter
│   │   └── marketdata/              ← MarketDataPort
│   ├── service/                      ← Domain services, strategies
│   │   ├── VolumeResolver.java       ← sealed interface
│   │   ├── PriceEvaluator.java
│   │   ├── MaterializationStrategy.java ← sealed interface
│   │   └── VolumeSeriesFactory.java
│   ├── command/                      ← TradeCapture, TradeAmend, TradeCancel
│   └── event/                        ← VolumePublished, VolumeSuperseded, ...
│
├── pv-persistence/                   ← JPA adapter
│   ├── entity/                       ← JPA entity classes (@Entity, @Table)
│   ├── repository/                   ← JpaVolumeSeriesRepository, JpaPositionLedgerRepository
│   ├── specification/                ← CriteriaBuilder-based query specs
│   ├── batch/                        ← BatchWriter
│   ├── audit/                        ← BitemporalAuditListener
│   └── datasource/                   ← DualHikariDataSourceRouter
│
├── pv-kafka/                         ← Kafka adapter
│   ├── producer/                     ← OutboxRelayProducer
│   └── consumer/                     ← IdempotentConsumer
│
├── pv-redis/                         ← Redis adapter
│   └── RedisVolumeCache.java
│
├── pv-guice/                         ← Guice wiring
│   ├── DomainModule.java
│   ├── PersistenceModule.java
│   ├── TenantModule.java
│   ├── EventModule.java
│   ├── CacheModule.java
│   ├── KafkaModule.java
│   └── PositionValuationApp.java     ← Bootstrap main
│
└── pv-spring-boot/                   ← Optional Spring Boot adapter (not covered here)
```

### 4.2 Dependency Skeletons (Maven)

**`pv-domain/pom.xml`**

```xml
<dependencies>
    <!-- ZERO framework dependencies. JDK only.
         JSR-330 is the sole exception — portable across Guice and Spring. -->
    <dependency>
        <groupId>jakarta.inject</groupId>
        <artifactId>jakarta.inject-api</artifactId>
        <version>2.0.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

**`pv-persistence/pom.xml`**

```xml
<dependencies>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-domain</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>jakarta.persistence</groupId>
        <artifactId>jakarta.persistence-api</artifactId>
        <version>3.2.0</version>
    </dependency>
    <dependency>
        <groupId>org.hibernate.orm</groupId>
        <artifactId>hibernate-core</artifactId>
        <version>7.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>6.2.1</version>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.5</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**`pv-kafka/pom.xml`**

```xml
<dependencies>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-domain</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>kafka-clients</artifactId>
        <version>3.7.2</version>
    </dependency>
</dependencies>
```

**`pv-redis/pom.xml`**

```xml
<dependencies>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-domain</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
        <version>5.2.0</version>
    </dependency>
</dependencies>
```

**`pv-guice/pom.xml`**

```xml
<dependencies>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-domain</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-persistence</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-kafka</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>pv-redis</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>com.google.inject</groupId>
        <artifactId>guice</artifactId>
        <version>7.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.google.inject.extensions</groupId>
        <artifactId>guice-persist</artifactId>
        <version>7.0.0</version>
    </dependency>
</dependencies>
```

> **TR-001** — `pv-domain` has zero compile-time dependencies on any framework. The sole exception is `jakarta.inject-api` (JSR-330), declared with `<scope>provided</scope>` because it is a portable annotation standard. No `com.google.inject.*`, no Spring, no Hibernate types appear in `pv-domain` source. (Extends FR-001, FR-050.)

---

## §5 — Domain Model: Value Objects, Events, Commands (`pv-domain`)

### 5.0 Numeric Precision Configuration — Pattern #33, FR-036, D-2, S1/S2/S3/S5a/S5c/S6/S6b/S7

> **TR-046** — All numeric scale and precision values are **system-wide configurable** per semantic domain via the `NumericPrecision` port interface. No domain or adapter code shall hardcode scale literals (e.g., `setScale(4, …)`); all rounding and column-precision references resolve through `NumericPrecision`. This enables commodity-specific or regulatory-driven precision overrides without code changes. (Extends FR-036, D-2, D-12.)

The system defines **six semantic precision domains**. Each domain carries an independent `scale` (decimal digits after the point) and `precision` (total significant digits) that govern both in-memory `BigDecimal` arithmetic and JPA `@Column` annotations.

| Domain | Semantic Scope | Default Scale | Default Precision | Examples |
|--------|---------------|---------------|-------------------|----------|
| `MONETARY` | Currency amounts, settlement values, mark-to-market | 4 | 18 | `Money.amount`, `SettlementCellEntity.amount`, `StruckMarkEntity.markValue` |
| `PRICE` | Price-per-unit values (EUR/MWh, USD/therm) |8| 15 | `SettlementCellEntity.price`, `PriceEvaluator` leaf results |
| `VOLUME` | Power capacity (MW), commodity quantity |8| 15 | `VolumeIntervalEntity.volume`, `PositionLedgerEntryEntity.quantity`, `CachedInterval.netMw` |
| `ENERGY` | Energy delivered (MWh), commodity energy equivalent |8| 18 | `VolumeIntervalEntity.energy`, `SettlementCellEntity.volumeMwh`, `CachedInterval.netMwh` |
| `MULTIPLIER` | Ratios, shares, allocation factors (0 < m ≤ 1) |8| 8 | `VolumeReference.multiplier`, `TradeIntervalCacheEntity.multiplier` |
| `INTERMEDIATE` | Scratch values during multi-step computation; not persisted | 10 | 20 | `PriceEvaluator` sub-expression results, time-weighted averages |

#### Port Interface (`pv-domain/port/`)

```java
/**
 * System-wide numeric scale and precision configuration.
 * Implementations are singletons bound in Guice; overrides per commodity
 * or regulatory regime are supported via named bindings or tenant-scoped providers.
 *
 * FR-036: precision conventions are externalized, not hardcoded.
 * D-12: commodity-neutral core — precision can vary per commodity deployment.
 */
public interface NumericPrecision {

    /** Decimal scale (digits after point) for the given domain. */
    int scale(Domain domain);

    /** Total precision (significant digits) for the given domain. */
    int precision(Domain domain);

    /** Default rounding mode applied system-wide. */
    default RoundingMode roundingMode() {
        return RoundingMode.HALF_UP;
    }

    /** Convenience: apply scale + rounding to a BigDecimal value. */
    default BigDecimal round(BigDecimal value, Domain domain) {
        return value.setScale(scale(domain), roundingMode());
    }

    /**
     * Semantic precision domains.
     * Each domain independently governs scale/precision for a category of numeric values.
     */
    enum Domain {
        /** Currency amounts: settlement values, mark-to-market, invoiced amounts. */
        MONETARY,
        /** Price-per-unit: EUR/MWh, USD/therm, index references. */
        PRICE,
        /** Power capacity / commodity quantity: MW, m³/h. */
        VOLUME,
        /** Energy delivered / commodity energy: MWh, therms. */
        ENERGY,
        /** Allocation ratios, shares, multipliers (0 < m ≤ 1). */
        MULTIPLIER,
        /** Scratch values in multi-step computation; never persisted. */
        INTERMEDIATE
    }
}
```

#### Default Implementation (`pv-domain`)

```java
/**
 * Immutable default precision configuration.
 * Suitable for EU power (EPEX/EEX/NORDPOOL/XBID) deployments.
 * Override via Guice @Named binding for commodity-specific precision
 * (e.g., gas = VOLUME scale 3, oil = PRICE scale 4).
 */
public record DefaultNumericPrecision() implements NumericPrecision {

    @Override
    public int scale(Domain domain) {
        return switch (domain) {
            case MONETARY    -> 4;
            case PRICE       -> 8;
            case VOLUME      -> 8;
            case ENERGY      -> 8;
            case MULTIPLIER  -> 8;
            case INTERMEDIATE -> 10;
        };
    }

    @Override
    public int precision(Domain domain) {
        return switch (domain) {
            case MONETARY    -> 20;
            case PRICE       -> 20;
            case VOLUME      -> 18;
            case ENERGY      -> 20;
            case MULTIPLIER  -> 10;
            case INTERMEDIATE -> 24;
        };
    }
}
```

> **TR-047** — JPA `@Column` annotations on numeric fields reference the configured precision/scale values **by documentation convention**: the annotated values must match the `NumericPrecision` defaults for the given domain. DDL generation and Flyway migrations source precision from the same configuration. When a tenant or commodity requires non-default precision, a `@Named("gas")` or tenant-scoped `NumericPrecision` binding overrides the default. (Extends FR-036, V2.0 §5.0.)

> **TR-048** — All `BigDecimal.setScale()` and `BigDecimal.divide(…, scale, …)` calls in domain code, adapters, and aggregators **must** resolve their scale argument through `NumericPrecision.scale(domain)` or `NumericPrecision.round(value, domain)`. Direct numeric literals for scale are prohibited outside of the `DefaultNumericPrecision` record itself. (Extends FR-036.)

---

### 5.1 Value Object Records — Pattern #3, #8, S1/S2/S3

> **TR-002** — All value objects are Java `record` types with validation at construction (static factories or compact constructors). No `Optional<T>` fields inside records. (Extends FR-036, D-2.)

#### `DeliveryPeriod` — Pattern #3, #8, FR-036, S1

```java
/**
 * Half-open delivery window [start, end) in market-local wall-clock.
 * Interval materialization via MarketCalendar (FR-025).
 */
public record DeliveryPeriod(
    ZonedDateTime start,
    ZonedDateTime end,
    ZoneId deliveryTimezone
) {
    public DeliveryPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(deliveryTimezone, "deliveryTimezone");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                "delivery end must be after start: [%s, %s)".formatted(start, end));
        }
    }

    public static DeliveryPeriod of(ZonedDateTime start, ZonedDateTime end, ZoneId zone) {
        return new DeliveryPeriod(
            start.withZoneSameInstant(zone),
            end.withZoneSameInstant(zone),
            zone
        );
    }

    /** True if this period contains the given instant. */
    public boolean contains(ZonedDateTime instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }
}
```

#### `SeriesKey` — Pattern #3, #8, FR-054, S3

```java
/**
 * Stable external key for a volume series. Survives amendments.
 * Examples: "FCST-WP-NORDSEE", "VS-T5500-1", "MTR-WP-NORDSEE".
 */
public record SeriesKey(String value) {
    public SeriesKey {
        Objects.requireNonNull(value, "seriesKey");
        if (value.isBlank()) {
            throw new IllegalArgumentException("seriesKey must not be blank");
        }
    }

    public static SeriesKey of(String prefix, String id) {
        return new SeriesKey(prefix + "-" + id);
    }

    @Override public String toString() { return value; }
}
```

#### `Money` — Pattern #3, #8, FR-036, D-2, S2/S5a

```java
/**
 * Monetary amount with currency. Scale governed by NumericPrecision.MONETARY (§5.0).
 * Arithmetic operations preserve currency; cross-currency arithmetic is a type error.
 */
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    /** Factory: rounds to MONETARY scale via NumericPrecision (TR-048). */
    public static Money of(BigDecimal amount, Currency currency, NumericPrecision np) {
        return new Money(np.round(amount, NumericPrecision.Domain.MONETARY), currency);
    }

    public static Money eur(BigDecimal amount, NumericPrecision np) {
        return of(amount, Currency.getInstance("EUR"), np);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /** Multiply and round to MONETARY scale (TR-048). */
    public Money multiply(BigDecimal factor, NumericPrecision np) {
        return new Money(np.round(amount.multiply(factor), NumericPrecision.Domain.MONETARY), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Cannot combine %s and %s".formatted(currency, other.currency));
        }
    }
}
```

#### `TimeRange` — Pattern #3, FR-036, S1/S3/S5a

```java
/**
 * Half-open time range [from, to) used for bitemporal axes and interval ranges.
 */
public record TimeRange(Instant from, Instant to) {
    public TimeRange {
        Objects.requireNonNull(from, "from");
        // 'to' may be null for open-ended ranges (e.g., current knowledge)
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
    }

    public static TimeRange open(Instant from) {
        return new TimeRange(from, null);
    }

    public static TimeRange closed(Instant from, Instant to) {
        return new TimeRange(from, to);
    }

    public boolean isOpen() { return to == null; }

    /** True if instant falls within [from, to). */
    public boolean contains(Instant instant) {
        return !instant.isBefore(from) && (to == null || instant.isBefore(to));
    }
}
```

#### `DeliveryRange` — Pattern #3, FR-030, S1

```java
/**
 * Delivery-month block range for position ledger entries.
 * Always aligned to calendar-month boundaries in market timezone.
 */
public record DeliveryRange(
    YearMonth startMonth,
    YearMonth endMonth,
    ZoneId deliveryTimezone
) {
    public DeliveryRange {
        Objects.requireNonNull(startMonth, "startMonth");
        Objects.requireNonNull(endMonth, "endMonth");
        Objects.requireNonNull(deliveryTimezone, "deliveryTimezone");
        if (endMonth.isBefore(startMonth)) {
            throw new IllegalArgumentException("endMonth must not be before startMonth");
        }
    }

    public static DeliveryRange ofMonth(YearMonth month, ZoneId zone) {
        return new DeliveryRange(month, month, zone);
    }

    public ZonedDateTime startInstant() {
        return startMonth.atDay(1).atStartOfDay(deliveryTimezone);
    }

    public ZonedDateTime endInstant() {
        return endMonth.plusMonths(1).atDay(1).atStartOfDay(deliveryTimezone);
    }
}
```

#### `VolumeReference` — Pattern #3, #6, FR-051, D-11, S3

```java
/**
 * Links a trade-leg to its volume source. Universal entry point for volume resolution.
 * trade_volume = volume_series_interval.volume × multiplier (D-11, V3.0 §3.3.3).
 *
 * For PPA: multiplier ∈ (0, 1], points to shared FORECAST series.
 * For DA/bilateral: multiplier = 1.0, points to dedicated PROFILE series.
 */
public record VolumeReference(
    UUID id,
    String tradeLegId,
    String tradeId,
    String assetId,           // nullable — set for asset-linked trades
    BigDecimal multiplier,
    SeriesKey volumeSeriesKey,
    SeriesKey meteredSeriesKey, // nullable — null for exchange/bilateral
    ZonedDateTime effectiveFrom,
    ZonedDateTime effectiveTo
) {
    public VolumeReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tradeLegId, "tradeLegId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(volumeSeriesKey, "volumeSeriesKey");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0 || multiplier.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("multiplier must be in (0, 1]: " + multiplier);
        }
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }

    public static Builder builder() { return new Builder(); }

    /** True if this is a degenerate (fixed-profile) reference. */
    public boolean isFixedProfile() {
        return multiplier.compareTo(BigDecimal.ONE) == 0 && assetId == null;
    }

    public static final class Builder {
        private UUID id;
        private String tradeLegId, tradeId, assetId;
        private BigDecimal multiplier;
        private SeriesKey volumeSeriesKey, meteredSeriesKey;
        private ZonedDateTime effectiveFrom, effectiveTo;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder tradeLegId(String v) { this.tradeLegId = v; return this; }
        public Builder tradeId(String v) { this.tradeId = v; return this; }
        public Builder assetId(String v) { this.assetId = v; return this; }
        public Builder multiplier(BigDecimal v) { this.multiplier = v; return this; }
        public Builder volumeSeriesKey(SeriesKey v) { this.volumeSeriesKey = v; return this; }
        public Builder meteredSeriesKey(SeriesKey v) { this.meteredSeriesKey = v; return this; }
        public Builder effectiveFrom(ZonedDateTime v) { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(ZonedDateTime v) { this.effectiveTo = v; return this; }

        public VolumeReference build() {
            return new VolumeReference(id, tradeLegId, tradeId, assetId,
                multiplier, volumeSeriesKey, meteredSeriesKey, effectiveFrom, effectiveTo);
        }
    }
}
```

### 5.2 Event Records — Pattern #14, #27, S3/S5a/S8

> **TR-003** — All domain events are `record` types in `pv-domain/event/`. They carry sufficient state for downstream routing without fetching the event source. (Extends FR-052a, V3.0 §8.)

#### `VolumePublished` — Pattern #14, #27, FR-052c, S3

```java
/**
 * Emitted when a new volume series version is created (first publication or re-materialization).
 * V3.0 §8.1.
 */
public record VolumePublished(
    SeriesKey seriesKey,
    VolumeLayer layer,
    SeriesType seriesType,     // nullable for METERED_ACTUAL
    long versionId,
    DeliveryPeriod deliveryRange,
    TimeGranularity granularity,
    QualityState qualityState,
    String scope,              // "FULL" or "PARTIAL"
    Instant eventTime
) {}
```

#### `VolumeSuperseded` — Pattern #14, #27, FR-052a, S3/S8

```java
/**
 * Primary revaluation trigger. Emitted when a volume series version is superseded.
 * event_time becomes known_from on resulting valuation-cell versions.
 * V3.0 §8.2, FR-052a.
 */
public record VolumeSuperseded(
    SeriesKey seriesKey,
    VolumeLayer layer,
    SeriesType seriesType,     // nullable for METERED_ACTUAL
    DeliveryPeriod affectedRange,
    Long oldVersionId,         // nullable for first publication
    long newVersionId,
    QualityState qualityState,
    Instant eventTime
) {}
```

#### `VolumeChunkMaterialized` — Pattern #14, FR-056, S3

```java
/**
 * Emitted when a chunk of a PARTIAL series is materialized (rolling-horizon extension).
 * V3.0 §8.3.
 */
public record VolumeChunkMaterialized(
    SeriesKey seriesKey,
    VolumeLayer layer,
    SeriesType seriesType,
    YearMonth chunkMonth,
    long versionId,
    int intervalCount,
    MaterializationStatus materializationStatus,
    Instant eventTime
) {}
```

#### `SettlementComputed` — Pattern #14, FR-071, S5a/S8

```java
/**
 * Emitted when a settlement cell is created or re-versioned.
 * Drives dependency-index edge updates (S8).
 */
public record SettlementComputed(
    UUID positionId,
    ZonedDateTime intervalStart,
    ZonedDateTime intervalEnd,
    Money value,
    String status,             // "PROVISIONAL" or "FINAL"
    Set<String> activeLeaves,
    Map<String, Long> inputVersionSet,
    Instant eventTime
) {}
```

### 5.3 Command Records — Pattern #17, FR-001–FR-005, S1

> **TR-004** — Commands are `record` types in `pv-domain/command/`. Each encapsulates a full transactional unit. Command handlers accept commands and interact with ports — never with framework types. (Extends FR-037.)

```java
/**
 * Captures a new trade position. Creates PositionLedgerEntry blocks
 * and VolumeReference(s) for volume resolution.
 */
public record TradeCapture(
    String tradeId,
    int tradeVersion,
    String tradeLegId,
    String tenantId,
    DeliveryPeriod deliveryPeriod,
    BigDecimal quantity,
    VolumeUnit volumeUnit,
    UUID priceExpressionId,
    String portfolioId,
    String deliveryPointId,
    String originType,         // EXCHANGE_FILL, BILATERAL_TRADE, ...
    Instant businessEffectiveDate,
    // Volume reference fields
    String assetId,            // nullable — set for PPA
    BigDecimal multiplier,
    SeriesKey volumeSeriesKey,
    SeriesKey meteredSeriesKey // nullable
) {}

/**
 * Amends an existing trade. Creates new ledger versions with valid-time
 * or knowledge-time adjustment per FR-008.
 */
public record TradeAmend(
    String tradeId,
    int tradeVersion,
    String tradeLegId,
    String tenantId,
    String amendmentReason,    // BACKDATED_CORRECTION or FORWARD_EFFECTIVE
    Instant businessEffectiveDate,
    // Fields that changed — nullable means "unchanged"
    BigDecimal quantity,
    UUID priceExpressionId,
    String portfolioId,
    BigDecimal multiplier,
    DeliveryPeriod deliveryPeriod
) {}

/**
 * Cancels a trade. Forward unwind closes valid_time; void-ab-initio
 * creates CANCELLED version (FR-038).
 */
public record TradeCancel(
    String tradeId,
    int tradeVersion,
    String tradeLegId,
    String tenantId,
    String cancellationType,   // FORWARD_UNWIND or VOID_AB_INITIO
    Instant businessEffectiveDate
) {}
```

### 5.4 Enums with Behavior — Pattern #4, #16, FR-054, S3

#### `QualityState` — Pattern #4, #16, FR-054, V3.0 §3.2.6

```java
/**
 * Quality progression state per volume layer.
 * Transition guards are methods — no framework dependency.
 */
public enum QualityState {
    // PROFILE series
    EFFECTIVE,
    AMENDED,
    // FORECAST series
    CURRENT,
    SUPERSEDED,
    // METERED_ACTUAL series
    PROVISIONAL,
    VALIDATED,
    ESTIMATED;

    /**
     * Guard: returns true if transition from this state to target is allowed.
     * PROFILE: EFFECTIVE → AMENDED
     * FORECAST: CURRENT → SUPERSEDED
     * METERED_ACTUAL: PROVISIONAL → VALIDATED, PROVISIONAL → ESTIMATED,
     *                 ESTIMATED → VALIDATED
     */
    public boolean canTransitionTo(QualityState target) {
        return switch (this) {
            case EFFECTIVE   -> target == AMENDED;
            case AMENDED     -> false;  // terminal
            case CURRENT     -> target == SUPERSEDED;
            case SUPERSEDED  -> false;  // terminal
            case PROVISIONAL -> target == VALIDATED || target == ESTIMATED;
            case VALIDATED   -> false;  // terminal
            case ESTIMATED   -> target == VALIDATED;
        };
    }

    /**
     * Performs transition or throws. Pure domain logic.
     * @throws IllegalStateTransitionException if transition is not allowed.
     */
    public QualityState transitionTo(QualityState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
        return target;
    }

    /** True if this state is applicable to PROFILE series. */
    public boolean isProfileState() { return this == EFFECTIVE || this == AMENDED; }

    /** True if this state is applicable to FORECAST series. */
    public boolean isForecastState() { return this == CURRENT || this == SUPERSEDED; }

    /** True if this state is applicable to METERED_ACTUAL series. */
    public boolean isMeteredState() {
        return this == PROVISIONAL || this == VALIDATED || this == ESTIMATED;
    }
}
```

#### `SeriesType` — Pattern #4, V3.0 §3.2.4, S3

```java
public enum SeriesType {
    /** Per asset (shared). Weather-model-sourced, frequently updated. */
    FORECAST,
    /** Per trade-leg (dedicated). Contractual, immutable after capture. */
    PROFILE
}
```

#### `VolumeUnit` — Pattern #4, V3.0 §3.2.2, S3

```java
public enum VolumeUnit {
    /** Power capacity in MW. Energy = volume × elapsed_hours. */
    MW_CAPACITY {
        @Override public BigDecimal toEnergy(BigDecimal volume, Duration elapsed, NumericPrecision np) {
            BigDecimal hours = BigDecimal.valueOf(elapsed.getSeconds())
                .divide(BigDecimal.valueOf(3600),
                    np.scale(NumericPrecision.Domain.ENERGY), np.roundingMode());
            return np.round(volume.multiply(hours), NumericPrecision.Domain.ENERGY);
        }
    },
    /** Energy delivered per period in MWh. Energy = volume. */
    MWH_PER_PERIOD {
        @Override public BigDecimal toEnergy(BigDecimal volume, Duration elapsed, NumericPrecision np) {
            return volume;
        }
    };

    /** Convert volume to energy; scale governed by NumericPrecision.ENERGY (TR-048). */
    public abstract BigDecimal toEnergy(BigDecimal volume, Duration elapsed, NumericPrecision np);

    public boolean isFixedDuration() { return true; }
}
```

#### `VolumeLayer` — V3.0 §3.2.5, S3

```java
public enum VolumeLayer {
    /** VolumeSeries (FORECAST or PROFILE). */
    VOLUME,
    /** MeteredActualVolumeSeries. */
    METERED_ACTUAL
}
```

#### `TimeGranularity` — V3.0 §3.2.1, S3

```java
public enum TimeGranularity {
    MIN_5(Duration.ofMinutes(5)),
    MIN_15(Duration.ofMinutes(15)),
    MIN_30(Duration.ofMinutes(30)),
    HOURLY(Duration.ofHours(1)),
    DAILY(null),
    MONTHLY(null);

    private final Duration fixedDuration;

    TimeGranularity(Duration fixedDuration) {
        this.fixedDuration = fixedDuration;
    }

    public boolean isFixedDuration() { return fixedDuration != null; }

    public Duration getFixedDuration() {
        if (fixedDuration == null) {
            throw new UnsupportedOperationException(
                name() + " has variable duration");
        }
        return fixedDuration;
    }

    public boolean isSubDaily() {
        return this == MIN_5 || this == MIN_15 || this == MIN_30 || this == HOURLY;
    }
}
```

#### `MaterializationStatus` — V3.0 §3.2.7, S3

```java
public enum MaterializationStatus {
    PENDING, PARTIAL, FULL, FAILED
}
```

---

## §6 — Domain Model: Aggregates & Entities

### 6.1 `VolumeSeries` + `VolumeInterval` — Pattern #2, FR-050, D-11, S3, V3.0 §3.3.1

> **TR-005** — `VolumeSeries` is an aggregate root. Intervals are ordered by `intervalStart` using `SequencedSet`. Ownership XOR constraint: exactly one of `assetId` / `tradeLegId` must be set. FORECAST requires `assetId`; PROFILE requires `tradeLegId`. (Extends FR-050, V3.0 §6.10.)

#### Domain Interface (`pv-domain`)

```java
/**
 * Unified volume series — FORECAST (per asset, shared) or PROFILE (per trade-leg, dedicated).
 * Consumers resolve volume identically: interval.volume × reference.multiplier.
 */
public interface VolumeSeries {
    UUID id();
    SeriesKey seriesKey();
    SeriesType seriesType();
    String assetId();          // non-null for FORECAST
    String tradeLegId();       // non-null for PROFILE
    long versionId();
    VolumeUnit volumeUnit();
    TimeGranularity granularity();
    DeliveryPeriod deliveryPeriod();
    QualityState qualityState();
    MaterializationStatus materializationStatus();
    YearMonth materializedThrough();
    int totalExpectedIntervals();
    int materializedIntervalCount();
    Instant transactionTime();
    Instant validTime();       // set for PROFILE (REMIT, FR-009g)

    /** Intervals ordered by intervalStart. */
    SequencedSet<VolumeInterval> intervals();
}
```

#### JPA Entity Skeleton (`pv-persistence`)

```java
@Entity
@Table(name = "volume_series", schema = "volume_series")
public class VolumeSeriesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator = "vs_seq")
    @SequenceGenerator(name = "vs_seq",
                       sequenceName = "volume_series.volume_series_seq",
                       allocationSize = 50)       // Pattern #23, P3
    private Long id;

    @Column(name = "series_uuid", nullable = false, unique = true)
    private UUID seriesUuid;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "series_key", length = 128, nullable = false)
    private String seriesKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "series_type", nullable = false)
    private SeriesType seriesType;

    @Column(name = "asset_id", length = 64)
    private String assetId;

    @Column(name = "trade_leg_id", length = 64)
    private String tradeLegId;

    @Column(name = "version_id", nullable = false)
    private long versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_state", nullable = false)
    private QualityState qualityState;

    @Enumerated(EnumType.STRING)
    @Column(name = "materialization_status", nullable = false)
    private MaterializationStatus materializationStatus;

    @Column(name = "transaction_time", nullable = false)
    private Instant transactionTime;

    @Column(name = "valid_time")
    private Instant validTime;

    // ... remaining columns per V2.0 §5.3

    @OneToMany(mappedBy = "series", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("intervalStart ASC")
    private List<VolumeIntervalEntity> intervals = new ArrayList<>();
}
```

**Key indexes** (defined in V2.0 §7.1, applied via Flyway):

| Index | Columns | Condition |
|-------|---------|-----------|
| `uq_vs_series_key_current` | `(tenant_id, series_key)` | `WHERE quality_state IN ('CURRENT', 'EFFECTIVE')` |
| `idx_vs_series_key_version` | `(tenant_id, series_key, version_id)` | — |
| `idx_vs_asset` | `(asset_id)` | `WHERE asset_id IS NOT NULL` |
| `idx_vs_trade_leg` | `(trade_leg_id)` | `WHERE trade_leg_id IS NOT NULL` |

#### `VolumeInterval` JPA Skeleton

```java
@Entity
@Table(name = "volume_interval", schema = "volume_series")
public class VolumeIntervalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator = "vi_seq")
    @SequenceGenerator(name = "vi_seq",
                       sequenceName = "volume_series.volume_interval_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "interval_uuid", nullable = false)
    private UUID intervalUuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private VolumeSeriesEntity series;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "volume", nullable = false)      // precision/scale: NumericPrecision.VOLUME (TR-047)
    private BigDecimal volume;

    @Column(name = "energy", nullable = false)      // precision/scale: NumericPrecision.ENERGY (TR-047)
    private BigDecimal energy;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "supersedes_id")
    private Long supersedesId;       // forward-link append-only versioning
}
```

### 6.2 `MeteredActualVolumeSeries` + `MeteredActualInterval` — Pattern #2, #34, S3, V3.0 §3.3.4

> **TR-006** — MeteredActual is append-only. Corrections insert new rows with `supersedes_id` pointing to the previous row. No UPDATE/DELETE at application layer; DB trigger `prevent_metered_actual_update()` enforces this (V2.0 §5.14). (Extends FR-050, P2.)

JPA entity structure mirrors `VolumeSeries` with the following differences:

| Column | Difference |
|--------|-----------|
| `asset_id` | Always non-null (per asset, not per trade) |
| `metering_point_id` | EIC-W reference (VARCHAR 128) |
| `received_at` | When TSO data arrived |
| `quality_state` | Restricted to PROVISIONAL, VALIDATED, ESTIMATED |
| No `trade_leg_id` | Never trade-specific |
| No `valid_time` | Not bitemporally versioned at series level |

### 6.3 `PositionLedgerEntry` — Pattern #1, #6, #34, #35, FR-030, D-1, S1

> **TR-007** — `PositionLedgerEntry` is the bitemporal source-of-truth entity. Grain: trade-leg × delivery-month block. Signed quantity (positive=long, negative=short). Append-only — versions superseded by closing `known_to` and appending a new row, never by in-place update. Builder pattern for complex construction. (Extends FR-030, FR-034, FR-037, D-1.)

#### Domain Model (`pv-domain`)

```java
/**
 * Bitemporal position ledger entry. One row per trade-leg per delivery-month per version.
 * Immutable after construction — all fields set at creation time via Builder.
 */
public final class PositionLedgerEntry {
    private final UUID id;
    private final String tenantId;
    private final String tradeId;
    private final String tradeLegId;
    private final int tradeVersion;
    private final DeliveryRange deliveryRange;
    private final BigDecimal quantity;          // signed: +long, -short
    private final VolumeUnit volumeUnit;
    private final UUID priceExpressionId;
    private final String portfolioId;
    private final String deliveryPointId;
    private final String originType;
    private final SeriesKey volumeSeriesKey;    // nullable for flat-block (FR-056a)
    private final String cascadeParentId;       // nullable (FR-03A)
    private final int cascadeGeneration;        // 0 for originals
    // Bitemporal axes
    private final Instant validFrom;
    private final Instant validTo;              // null = open-ended
    private final Instant knownFrom;
    private final Instant knownTo;              // null = current knowledge
    private final String status;                // ACTIVE, SUPERSEDED, CANCELLED
    private final String amendmentReason;       // BACKDATED_CORRECTION, FORWARD_EFFECTIVE

    private PositionLedgerEntry(Builder b) { /* assign all fields */ }

    public static Builder builder() { return new Builder(); }

    // Accessors (getters only, no setters)
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public DeliveryRange deliveryRange() { return deliveryRange; }
    public BigDecimal quantity() { return quantity; }
    public boolean isCurrentKnowledge() { return knownTo == null; }
    // ... remaining accessors

    public static final class Builder {
        // all fields with setters returning this
        public PositionLedgerEntry build() {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(tradeLegId, "tradeLegId");
            Objects.requireNonNull(deliveryRange, "deliveryRange");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(volumeUnit, "volumeUnit");
            Objects.requireNonNull(priceExpressionId, "priceExpressionId");
            Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(knownFrom, "knownFrom");
            return new PositionLedgerEntry(this);
        }
    }
}
```

#### JPA Entity Skeleton (`pv-persistence`)

```java
@Entity
@Table(name = "position_ledger_entry", schema = "position")
@EntityListeners(BitemporalAuditListener.class)     // Pattern #35
public class PositionLedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator = "ple_seq")
    @SequenceGenerator(name = "ple_seq",
                       sequenceName = "position.position_ledger_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "entry_uuid", nullable = false, unique = true)
    private UUID entryUuid;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "trade_id", length = 64, nullable = false)
    private String tradeId;

    @Column(name = "trade_leg_id", length = 64, nullable = false)
    private String tradeLegId;

    @Column(name = "trade_version", nullable = false)
    private int tradeVersion;

    // Delivery-month block (D-1, FR-030)
    @Column(name = "delivery_start", nullable = false)
    private Instant deliveryStart;

    @Column(name = "delivery_end", nullable = false)
    private Instant deliveryEnd;

    @Column(name = "delivery_timezone", length = 64, nullable = false)
    private String deliveryTimezone;

    @Column(name = "quantity", nullable = false)     // precision/scale: NumericPrecision.VOLUME (TR-047)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "volume_unit", nullable = false)
    private VolumeUnit volumeUnit;

    @Column(name = "price_expression_id", nullable = false)
    private UUID priceExpressionId;

    @Column(name = "volume_series_key", length = 128)
    private String volumeSeriesKey;       // nullable for flat-block (FR-056a)

    // Bitemporal axes — stored as UTC timestamptz (TR-018)
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;              // null = open-ended

    @Column(name = "known_from", nullable = false)
    private Instant knownFrom;

    @Column(name = "known_to")
    private Instant knownTo;              // null = current knowledge

    @Column(name = "status", length = 16, nullable = false)
    private String status;                // ACTIVE, SUPERSEDED, CANCELLED

    // Cascade lineage (FR-03A)
    @Column(name = "cascade_parent_id", length = 64)
    private String cascadeParentId;

    @Column(name = "cascade_generation", nullable = false)
    private int cascadeGeneration = 0;
}
```

> **TR-018** — `PositionLedgerEntry` bitemporal columns (`valid_from`, `valid_to`, `known_from`, `known_to`) are stored as UTC-normalised `timestamptz`. All comparison predicates use `Instant` (UTC). Market-local semantics apply only to `delivery_start`/`delivery_end`. (Extends FR-006.)

**Key indexes** (applied via Flyway):

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_ple_tenant_trade` | `(tenant_id, trade_id, trade_leg_id)` | Trade-leg lookup |
| `idx_ple_tenant_delivery` | `(tenant_id, delivery_start, delivery_end)` | Delivery-month pruning |
| `idx_ple_current_knowledge` | `(tenant_id, trade_id, known_to)` | Current-state filter: `WHERE known_to IS NULL` |
| `idx_ple_bitemporal` | `(tenant_id, valid_from, valid_to, known_from, known_to)` | As-of reconstruction (FR-007) |

---

## §7 — PriceExpression: Sealed Hierarchy (S2)

### 7.1 Sealed Interface — Pattern #5, D-2, FR-048h, S2

> **TR-008** — `PriceExpression` is a `sealed interface` with 13 permitted types (3 leaf + 10 operator) per FR-048h. Every expression is an immutable `record`. A fixed price is a degenerate tree with a single `ConstantLeaf`. (Extends D-2, FR-040, FR-048.)

```java
/**
 * Sealed expression tree for price resolution. All types immutable records.
 * Fixed price = ConstantLeaf(85.00). Full PPA = nested tree with 6+ leaves.
 * FR-048h, D-2.
 */
public sealed interface PriceExpression permits
    // Leaf types (terminals) — FR-048a
    ConstantLeaf,
    MarketDataLeaf,
    IndexLeaf,
    // Operator types (non-terminals) — FR-048b
    Add,
    Subtract,
    Multiply,
    Divide,
    Clamp,
    Escalate,
    ConditionalGate,
    ConditionalPassThrough,
    TimeAverage,
    FxConvert { }
```

### 7.2 Leaf Types — FR-048a

```java
/**
 * Fixed numeric value with unit. Always active unless pruned by ancestor gate.
 * Example: 85.00 EUR/MWh (fixed price), 42.00 (collar floor base), 105.2 (CPI base).
 */
public record ConstantLeaf(
    String leafId,
    BigDecimal value,
    String unit
) implements PriceExpression {}

/**
 * Reference to a market data series (S4) with quotation parameters.
 * Carries optional settlementSeries for purpose-based resolution (FR-048e).
 * Example: {series: "EPEX-DE-LU-DA15", lag: 0, window: SINGLE_INTERVAL}.
 */
public record MarketDataLeaf(
    String leafId,
    String series,             // primary series (forward curve)
    String settlementSeries,   // nullable — settlement-specific series (FR-048e)
    int lag,
    String quotationWindow     // SINGLE_INTERVAL, DAILY, MONTHLY
) implements PriceExpression {}

/**
 * Reference to macro index with reference-month mapping.
 * Example: {series: "HICP-DE", refMonth: deliveryYear-1 + November}.
 */
public record IndexLeaf(
    String leafId,
    String series,
    String refMonthExpression  // e.g., "deliveryYear-1:November"
) implements PriceExpression {}
```

### 7.3 Operator Types — FR-048b

```java
public record Add(PriceExpression left, PriceExpression right) implements PriceExpression {}
public record Subtract(PriceExpression left, PriceExpression right) implements PriceExpression {}
public record Multiply(PriceExpression left, PriceExpression right) implements PriceExpression {}
public record Divide(PriceExpression numerator, PriceExpression denominator)
    implements PriceExpression {}

/** Floor/cap/collar: max(min, min(max, inner)). FR-048b. */
public record Clamp(
    PriceExpression min,
    PriceExpression max,
    PriceExpression inner
) implements PriceExpression {}

/** Base × ratio. Ratio typically index / base_value. FR-048b. */
public record Escalate(
    PriceExpression base,
    PriceExpression ratio
) implements PriceExpression {}

/**
 * If gate_input meets condition → override with overrideValue; else → evaluate inner.
 * Negative-price zeroing: if DA < 0 then 0.00 else inner. FR-042a, FR-048b.
 */
public record ConditionalGate(
    PriceExpression gateInput,
    String condition,          // e.g., "< 0"
    PriceExpression overrideValue,
    PriceExpression inner
) implements PriceExpression {}

/**
 * If gate_input meets condition → pass gate_input as result; else → evaluate inner.
 * Negative-price pass-through: if DA < 0 then DA else inner. FR-042a.
 */
public record ConditionalPassThrough(
    PriceExpression gateInput,
    String condition,
    PriceExpression inner
) implements PriceExpression {}

/** Average of child over quotation window (e.g., monthly avg of daily fixings). */
public record TimeAverage(
    PriceExpression child,
    String windowSpec          // e.g., "MONTHLY"
) implements PriceExpression {}

/** Child × FX rate for cross-currency deals. */
public record FxConvert(
    PriceExpression value,
    PriceExpression fxRate
) implements PriceExpression {}
```

### 7.4 `PriceEvaluator` Port — Pattern #10, FR-045, S2

> **TR-009** — `PriceEvaluator` is a port interface in `pv-domain`. It resolves a `PriceExpression` tree for a given interval, returning `(value, active_leaves, input_version_set)`. Purpose-based leaf resolution (FR-048e): forward marks use forward curve, settlement uses DA settlement series. (Extends FR-045, FR-046, FR-047.)

```java
/**
 * Result of evaluating a PriceExpression tree for one interval.
 * FR-045: (value, active_leaves, input_version_set).
 */
public record PriceResolution(
    BigDecimal value,
    Set<String> activeLeaves,
    Map<String, Long> inputVersionSet
) {}

/**
 * Evaluation purpose — determines which market data series to use (FR-048e).
 */
public enum ResolutionPurpose { FORWARD, SETTLEMENT }

/**
 * Port interface for price expression evaluation. Lives in pv-domain.
 * Implementation dispatches via pattern-matching switch over the sealed hierarchy.
 */
@FunctionalInterface
public interface PriceEvaluator {

    /**
     * Evaluate expression tree for a single interval.
     * @param expression  the expression tree (sealed hierarchy)
     * @param interval    the delivery interval being priced
     * @param purpose     FORWARD or SETTLEMENT (drives leaf resolution per FR-048e)
     * @param marketData  port to look up market data at correct version
     * @return resolution with value, active_leaves, and input_version_set
     */
    PriceResolution evaluate(
        PriceExpression expression,
        DeliveryPeriod interval,
        ResolutionPurpose purpose,
        MarketDataPort marketData
    );
}
```

### 7.5 Pattern-Matching Switch Excerpt — Pattern #5, #10, #12, FR-048h

> **TR-010** — The `PriceEvaluator` implementation uses an exhaustive pattern-matching `switch` over the sealed `PriceExpression` hierarchy. Adding a new operator type produces compile errors at all unhandled dispatch points. (Extends FR-048h.)

```java
/**
 * Representative tree-walker excerpt (pv-domain service, not adapter).
 * Shows exhaustive dispatch for 6 of the 13 types.
 */
/** NumericPrecision injected at construction (TR-048). */
private final NumericPrecision np;

public PriceResolution evaluate(
        PriceExpression expr,
        DeliveryPeriod interval,
        ResolutionPurpose purpose,
        MarketDataPort marketData) {

    var activeLeaves = new HashSet<String>();
    var versions = new HashMap<String, Long>();

    BigDecimal result = eval(expr, interval, purpose, marketData, activeLeaves, versions);

    return new PriceResolution(np.round(result, NumericPrecision.Domain.PRICE),
            Set.copyOf(activeLeaves), Map.copyOf(versions));
}

private BigDecimal eval(
        PriceExpression expr,
        DeliveryPeriod interval,
        ResolutionPurpose purpose,
        MarketDataPort md,
        Set<String> activeLeaves,
        Map<String, Long> versions) {

    return switch (expr) {

        case ConstantLeaf c -> {
            activeLeaves.add(c.leafId());
            yield c.value();
        }

        case MarketDataLeaf m -> {
            // Purpose-based resolution (FR-048e)
            String series = (purpose == ResolutionPurpose.SETTLEMENT
                             && m.settlementSeries() != null)
                            ? m.settlementSeries()
                            : m.series();
            var lookup = md.lookupFixing(series, interval.start());
            activeLeaves.add(m.leafId());
            versions.put(series, lookup.versionId());
            yield lookup.value();
        }

        case IndexLeaf i -> {
            var lookup = md.lookupIndex(i.series(), i.refMonthExpression(), interval);
            activeLeaves.add(i.leafId());
            versions.put(i.series(), lookup.versionId());
            yield lookup.value();
        }

        case Clamp cl -> {
            BigDecimal inner = eval(cl.inner(), interval, purpose, md, activeLeaves, versions);
            // Evaluate bounds in separate leaf sets to detect binding (FR-048f)
            var boundLeaves = new HashSet<String>();
            BigDecimal min = eval(cl.min(), interval, purpose, md, boundLeaves, versions);
            BigDecimal max = eval(cl.max(), interval, purpose, md, boundLeaves, versions);
            BigDecimal clamped = inner.max(min).min(max);
            if (clamped.compareTo(inner) != 0) {
                // Bound is binding — bound leaves become active
                activeLeaves.addAll(boundLeaves);
            }
            // else: inside collar — bound leaves stay inactive (FR-048f)
            yield clamped;
        }

        case Escalate e -> {
            BigDecimal base = eval(e.base(), interval, purpose, md, activeLeaves, versions);
            BigDecimal ratio = eval(e.ratio(), interval, purpose, md, activeLeaves, versions);
            yield np.round(base.multiply(ratio), NumericPrecision.Domain.PRICE);
        }

        case ConditionalGate g -> {
            BigDecimal gateVal = eval(g.gateInput(), interval, purpose, md, activeLeaves, versions);
            if (meetsCondition(gateVal, g.condition())) {
                // Gate fires — only gate input is active; inner tree inactive (FR-048f)
                yield eval(g.overrideValue(), interval, purpose, md, activeLeaves, versions);
            } else {
                yield eval(g.inner(), interval, purpose, md, activeLeaves, versions);
            }
        }

        case ConditionalPassThrough pt -> {
            BigDecimal gateVal = eval(pt.gateInput(), interval, purpose, md, activeLeaves, versions);
            if (meetsCondition(gateVal, pt.condition())) {
                yield gateVal;  // pass-through: gate value IS the result
            } else {
                yield eval(pt.inner(), interval, purpose, md, activeLeaves, versions);
            }
        }

        case Add a ->
            eval(a.left(), interval, purpose, md, activeLeaves, versions)
                .add(eval(a.right(), interval, purpose, md, activeLeaves, versions));

        case Subtract s ->
            eval(s.left(), interval, purpose, md, activeLeaves, versions)
                .subtract(eval(s.right(), interval, purpose, md, activeLeaves, versions));

        case Multiply mu -> {
            BigDecimal product = eval(mu.left(), interval, purpose, md, activeLeaves, versions)
                .multiply(eval(mu.right(), interval, purpose, md, activeLeaves, versions));
            yield np.round(product, NumericPrecision.Domain.INTERMEDIATE);
        }

        case Divide d -> {
            BigDecimal quotient = eval(d.numerator(), interval, purpose, md, activeLeaves, versions)
                .divide(eval(d.denominator(), interval, purpose, md, activeLeaves, versions),
                        np.scale(NumericPrecision.Domain.INTERMEDIATE), np.roundingMode());
            yield quotient;
        }

        case TimeAverage ta -> {
            // Delegate to MarketDataPort for windowed average
            BigDecimal avg = eval(ta.child(), interval, purpose, md, activeLeaves, versions);
            yield avg;  // simplified — full impl aggregates over window
        }

        case FxConvert fx -> {
            BigDecimal val = eval(fx.value(), interval, purpose, md, activeLeaves, versions);
            BigDecimal rate = eval(fx.fxRate(), interval, purpose, md, activeLeaves, versions);
            yield np.round(val.multiply(rate), NumericPrecision.Domain.MONETARY);
        }
    };
}
```

**Exhaustiveness guarantee:** This `switch` covers all 13 permitted subtypes. If a new operator type is added to the `permits` clause (flagged in Open Items per ADR-001), the compiler produces errors at every `switch` statement that does not handle the new type.

---

*End of Part 1. Continue to [Part 2 — Subsystems (§8–§13)](TECH-SPEC-position-valuation-library_guice-v1.0-part2-subsystems.md).*
