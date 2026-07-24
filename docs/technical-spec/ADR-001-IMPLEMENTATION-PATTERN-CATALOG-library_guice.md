# ADR-001: Implementation Pattern Catalog for Position & Valuation System (Library + Guice)

| Field | Value |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-07-24 |
| **Deciders** | Architecture team |
| **Companion Specs** | `functional-spec-position-valuation-v1.0.md`, `VOLUME_SERIES_SPEC-V3_0.md`, `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` |
| **Variant** | Framework-agnostic core library with Google Guice DI; Spring Boot as optional adapter |
| **See also** | `ADR-001-IMPLEMENTATION-PATTERN-CATALOG-springboot.md` — Spring Boot variant of this ADR |

---

## 1. Context

The Position & Valuation system is a multitenant SaaS platform for EU power CTRM (~200 tenants) that must handle bitemporal position tracking, formula-based pricing (PPAs with collars, escalation, negative-price gates), unified volume resolution via `VolumeReference × multiplier`, event-driven revaluation, and regulatory-grade auditability (REMIT, EMIR, MiFID II).

The system comprises eight subsystems (S1–S8 plus S6b) spanning domain entities, derived measures, caches, and integration boundaries. The functional spec (V1.0) fixes twelve design decisions (D-1 through D-12) and defines ~120 functional rules (FR-nnn). The volume domain spec (V3.0) introduces the unified model. The data architecture (V2.0) specifies the persistence layer on Aurora PostgreSQL 16.

**Why this variant:** The system is designed as a **framework-agnostic core library** (`pv-domain`) with zero runtime dependency on Spring, Guice, or any framework. Domain logic, value objects, strategies, and port interfaces live in pure Java 21 code. Google Guice serves as the lightweight DI container for wiring adapters (persistence, messaging, caching) to domain ports. Spring Boot remains available as an alternative adapter for teams that prefer it — the core library does not change.

**Architecture:** Hexagonal (Ports & Adapters). The domain core defines port interfaces; adapter modules implement them. Guice modules bind adapters to ports. This separation ensures the domain logic is independently testable, reusable, and framework-portable.

**Platform:** Java 21 / Google Guice 7 / JPA 3.2 (Hibernate 7.x, Jakarta EE 11) / Aurora PostgreSQL 16 / Kafka 3.7 (KRaft) / Redis 7 (Jedis/Lettuce) / pg_partman + pg_cron / Flyway / HikariCP + PgBouncer.

---

## 1.1 Module Structure

```
power-position-valuation/
├── pv-domain/                        ← Pure Java 21, ZERO framework dependencies
│   ├── model/                        ← Records, sealed types, enums, aggregates
│   │   ├── PositionLedgerEntry.java
│   │   ├── VolumeSeries.java, VolumeInterval.java
│   │   ├── VolumeReference.java
│   │   ├── PriceExpression.java      ← sealed interface (FR-048h)
│   │   ├── expression/              ← Leaf types (ConstantLeaf, MarketDataLeaf, IndexLeaf)
│   │   │                              Operator types (Clamp, Escalate, ConditionalGate, ...)
│   │   ├── QualityState.java         ← enum with transition guards
│   │   └── value/                    ← DeliveryPeriod, SeriesKey, Money, TimeRange (records)
│   ├── port/                         ← Interfaces (SPI) — the hexagonal boundary
│   │   ├── repository/               ← VolumeSeriesRepository, PositionLedgerRepository
│   │   ├── event/                    ← DomainEventPublisher, DomainEventSubscriber
│   │   ├── cache/                    ← VolumeCache (read-through, invalidation)
│   │   ├── tenant/                   ← TenantContext (current tenant resolution)
│   │   └── datasource/              ← DataSourceRouter (writer/reader split)
│   ├── service/                      ← Domain services, strategies, state machines
│   │   ├── VolumeResolver.java       ← sealed interface
│   │   ├── PriceEvaluator.java       ← sealed interface
│   │   ├── MaterializationStrategy.java
│   │   └── QualityStateTransition.java
│   ├── command/                      ← TradeCapture, TradeAmend, TradeCancel (records)
│   └── event/                        ← VolumePublished, VolumeSuperseded (records)
│
├── pv-persistence/                   ← JPA adapter — implements repository ports
│   ├── repository/                   ← JpaVolumeSeriesRepository, JpaPositionLedgerRepository
│   ├── specification/                ← JPA CriteriaBuilder-based query specifications
│   ├── batch/                        ← BatchWriter (flush/clear every 50 entities)
│   ├── audit/                        ← BitemporalAuditListener (JPA entity listener)
│   ├── datasource/                   ← DualDataSourceRouter (writer/reader HikariCP pools)
│   └── build.gradle                  ← deps: jakarta.persistence-api 3.2, hibernate-core 7.x, pv-domain
│
├── pv-kafka/                         ← Kafka adapter — implements event ports
│   ├── producer/                     ← OutboxRelayProducer (polls outbox → Kafka)
│   ├── consumer/                     ← IdempotentConsumer (trade.captured, forecast.published)
│   └── build.gradle                  ← deps: kafka-clients, pv-domain
│
├── pv-redis/                         ← Redis adapter — implements cache ports
│   ├── RedisVolumeCache.java         ← Read-through, MGET batching, event invalidation
│   └── build.gradle                  ← deps: jedis or lettuce, pv-domain
│
├── pv-guice/                         ← Guice wiring — binds adapters to ports
│   ├── DomainModule.java             ← Binds domain services and strategies
│   ├── PersistenceModule.java        ← Binds JPA repos, EntityManager, dual DataSource
│   ├── TenantModule.java             ← TenantInterceptor, TenantContext binding
│   ├── EventModule.java              ← DomainEventPublisher binding (in-process or Kafka)
│   ├── CacheModule.java              ← VolumeCache → RedisVolumeCache
│   └── build.gradle                  ← deps: guice 7, pv-domain, pv-persistence, pv-kafka, pv-redis
│
└── pv-spring-boot/                   ← (Optional) Spring Boot adapter — alternative to pv-guice
    ├── AutoConfiguration.java        ← Bridges pv-domain ports to Spring beans
    └── build.gradle                  ← deps: spring-boot-starter-data-jpa, pv-domain, pv-persistence
```

**Dependency rule:** `pv-domain` depends on nothing except the JDK. All adapter modules depend on `pv-domain`. `pv-guice` (or `pv-spring-boot`) depends on all adapter modules. No adapter depends on another adapter.

---

## 2. Decision: Pattern Catalog

### 2.1 Category 1 — Domain Model Patterns (5 patterns)

*Identical to Spring Boot variant. All patterns live in `pv-domain` with zero framework dependencies.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 1 | Entity vs Measure distinction | `PositionLedgerEntry` is an entity (lifecycle-bearing, bitemporal); `SettlementCell`, slot cache entries, forward marks are measures (coordinate-identified, derived, rebuildable) | D-1, D-8, FR-001–FR-003 | — |
| 2 | Aggregate Root | `VolumeSeries` (root) → `VolumeInterval` (child, ordered by `intervalStart`); `MeteredActualVolumeSeries` → `MeteredActualInterval`; `PositionLedgerEntry` as standalone root | V3.0 §3.1, §3.3.1, FR-050 | `SequencedCollection` for ordered children |
| 3 | Value Object (Java `record`) | `DeliveryPeriod`, `TimeGranularity`, `SeriesKey`, `Money`, `TimeRange`, `DeliveryRange` — immutable, equality by value, validated at construction | FR-001, FR-036, D-2, V3.0 §3.2 | `record` types |
| 4 | Enum with behavior | `QualityState` with allowed transition guards (PROVISIONAL→VALIDATED→FINAL for metered; EFFECTIVE→AMENDED for profile; CURRENT→SUPERSEDED for forecast); `SeriesType`, `VolumeUnit` with `isFixedDuration()`, `isSubDaily()` | FR-054, V3.0 §3.2.4–§3.2.6 | Enhanced `enum` with methods |
| 5 | Sealed type hierarchy | `PriceExpression` sealed interface with leaf types (`ConstantLeaf`, `MarketDataLeaf`, `IndexLeaf`) and operator types (`Add`, `Subtract`, `Multiply`, `Divide`, `Clamp`, `Escalate`, `ConditionalGate`, `ConditionalPassThrough`, `TimeAverage`, `FxConvert`). A fixed price is a degenerate tree with a single `ConstantLeaf`. A full PPA formula composes 5+ leaves and 4+ operators (collar + CPI escalation + neg-price gate). One tree walker resolves all expression types; `active_leaves` output drives blast-radius optimization (D-4) | D-2, FR-040, FR-048–FR-048h, FR-020–FR-025 | `sealed interface … permits` + pattern matching `switch` |

### 2.2 Category 2 — Creational Patterns (3 patterns)

*Identical to Spring Boot variant. All patterns live in `pv-domain`.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 6 | Builder | `PositionLedgerEntry.builder()` — complex construction with bitemporal axes (`validFrom/To`, `knownFrom/To`), signed quantity, delivery range, price expression ref; `VolumeReference.builder()` — multiplier, effective dates, series keys | D-1, D-11, FR-030–FR-036 | — |
| 7 | Factory Method | `VolumeSeriesFactory.createForTrade()` — routes to PROFILE (per-trade, `multiplier=1.0`) vs FORECAST (per-asset, shared) based on trade type; both return `VolumeSeries` but with different ownership and lifecycle characteristics | D-11, FR-050, V3.0 §2.3 | — |
| 8 | Static Factory | `Money.of(amount, currency)`, `DeliveryPeriod.of(start, end, zone)`, `SeriesKey.of(prefix, id)` — validation at construction (fail-fast), consistent naming, no invalid instances | D-2, FR-036, V3.0 §3.2 | `record` with static factory returning `this` type |

### 2.3 Category 3 — Structural Patterns (5 patterns)

*Strategy interfaces live in `pv-domain/port/` or `pv-domain/service/`. The Decorator/Filter Chain implementation moves from Spring AOP to Guice `MethodInterceptor`.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 9 | Strategy — Volume Resolution | `VolumeResolver` sealed interface in `pv-domain` with `ProfileResolver` and `ForecastResolver`. In practice, one code path — the "strategy" is the data properties, not branching code (D-11: no category branching). Implementations are pure domain logic, no framework dependency | D-11, FR-050–FR-051, V3.0 §2.3 | `sealed interface` |
| 10 | Strategy — Price Evaluation | Tree walker in `pv-domain` dispatches per node type via pattern matching `switch`: `ConstantLeaf` → return value; `MarketDataLeaf` → lookup from `MarketDataPort` at correct version; `IndexLeaf` → macro index with ref-month mapping; `Clamp` → floor/cap/collar; `Escalate` → base × CPI ratio; `ConditionalGate` → neg-price zeroing; `ConditionalPassThrough` → neg-price pass-through (FR-042a). Purpose-based leaf resolution (FR-048e): forward marks use forward curve, settlement uses DA settlement series. Market data access via `MarketDataPort` interface, not direct DB call | D-2, FR-020, FR-040, FR-048–FR-048f, §6.4–§6.5 | Pattern matching `switch` over `sealed` types |
| 11 | Strategy — Materialization | `MaterializationStrategy` interface in `pv-domain`: `EagerStrategy`, `RollingHorizonStrategy`, `ChunkStrategy`. Persistence via `VolumeSeriesRepository` port — strategy does not know whether JPA, JDBC, or a mock backs it | FR-056, V3.0 §4.1–§4.3, S6b | — |
| 12 | Composite | `PriceExpression` tree in `pv-domain` — recursive `evaluate(interval, marketData)`. Leaf nodes are terminals (`ConstantLeaf`, `MarketDataLeaf`, `IndexLeaf`); operator nodes compose children (`Clamp` for collar, `Escalate` for CPI, `ConditionalGate` for neg-price zeroing, `FxConvert` for cross-currency). Clause precedence encoded per expression (contract law, not hard-coded — FR-042). Resolution emits `(value, active_leaves, input_version_set)` per FR-048f: collar-inside → CPI inactive; neg-gate fires → everything below inactive. Complexity ranges from 1 leaf / 0 operators (fixed price) to 6+ leaves / 5+ operators (full PPA — FR-048d). Entirely in `pv-domain` | D-2, FR-025, FR-042, FR-048d–FR-048g, §6.2, §6.5 | Pattern matching for tree traversal |
| 13 | Decorator / Filter Chain | `TenantContextFilter` → `AuditFilter` → `RequestHandler`. **Guice variant:** Guice `MethodInterceptor` bound via `bindInterceptor(Matchers.annotatedWith(TenantAware.class), ...)` in `TenantModule`. The `@TenantAware` annotation itself lives in `pv-domain`; the interceptor implementation lives in `pv-guice` | O-2, FR-075, V2.0 §11, P1 | — |

### 2.4 Category 4 — Behavioral Patterns (4 patterns)

*Event payloads are records in `pv-domain/event/`. The Observer pattern uses a `DomainEventPublisher` port interface instead of Spring's `ApplicationEventPublisher`.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 14 | Observer / Domain Events | `VolumePublished`, `VolumeSuperseded`, `VolumeChunkMaterialized`, `SettlementComputed` — all `record` types in `pv-domain/event/`. Published via `DomainEventPublisher` port interface. **Guice variant:** bound to an in-process event bus (Guava `EventBus` or custom) for unit testing, or to a Kafka-backed implementation (`pv-kafka`) for production | V3.0 §8.1–§8.3, FR-052a–FR-052c, V2.0 §13.2 | `record` for event payloads |
| 15 | Template Method | `AbstractMaterializationJob` in `pv-domain/service/` with hooks: `resolveVolume()`, `evaluatePrice()`, `writeResult()`. Concrete implementations for settlement (S5a), forward marks (S5b), slot cache (S6), trade interval cache (S6b). Repository and cache ports are constructor-injected via `@Inject` (JSR-330) | FR-056, S5a/S5b/S6/S6b | — |
| 16 | State Machine | `QualityState` transitions with guard conditions. Pure enum logic in `pv-domain` — no framework dependency. Guards are methods on the enum: `canTransitionTo(QualityState target)`, `transitionTo(QualityState target)` throws `IllegalStateTransitionException` | FR-054, V3.0 §3.2.6, P2 | Enum with `transitionTo(target)` method + guard |
| 17 | Command | `TradeCapture`, `TradeAmend`, `TradeCancel` — `record` types in `pv-domain/command/`. Each encapsulates a full transactional unit. Command handlers in `pv-domain/service/` accept commands and interact with ports. Transaction boundary managed by the Guice `@Transactional` interceptor (from `guice-persist`) or a custom `UnitOfWork` wrapper | FR-001–FR-005, FR-037–FR-038, V2.0 §13.1 | `record` for command payloads |

### 2.5 Category 5 — Persistence Patterns (6 patterns)

*This is where the library variant differs most from Spring Boot. Repository interfaces are ports in `pv-domain`; implementations live in `pv-persistence`. No Spring Data JPA — repositories are hand-written JPA using `EntityManager`.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 18 | Repository (Port + Adapter) | **Port:** `VolumeSeriesRepository`, `PositionLedgerRepository`, `SettlementCellRepository` — plain Java interfaces in `pv-domain/port/repository/`. Define methods like `findBySeriesKey(String key)`, `save(VolumeSeries series)`. No JPA, Spring, or Guice annotations. **Adapter:** `JpaVolumeSeriesRepository implements VolumeSeriesRepository` in `pv-persistence/`, uses injected `EntityManager` directly. Guice binds adapter to port in `PersistenceModule` | V2.0 §5, P1, V3.0 §7.1 | — |
| 19 | Specification (query object) | `VolumeSeriesSpec` in `pv-domain/port/repository/` — a functional interface: `Predicate<CriteriaBuilder, CriteriaQuery<?>, Root<VolumeSeries>>`. Composable via `.and()`, `.or()`. The `pv-persistence` adapter translates these to JPA `CriteriaQuery` predicates. Example: `VolumeSeriesSpec.byTenantAndAsset(tenantId, assetId).and(VolumeSeriesSpec.currentVersionOnly())` | FR-051, V3.0 §7.1, V2.0 §7 | Functional interfaces, `@FunctionalInterface` |
| 20 | Unit of Work (batch flush) | `BatchWriter` utility in `pv-persistence/batch/`: wraps `EntityManager.persist()` calls with periodic `flush()` + `clear()` every 50 entities. Injected with `@Inject Provider<EntityManager>` via Guice. Hibernate `hibernate.jdbc.batch_size=50` configured in `persistence.xml` | V2.0 §17.1–§17.2, P3 | — |
| 21 | Identity Map | JPA first-level cache within transaction boundary — same behavior as Spring variant. Transaction boundary defined by Guice `@Transactional` (from `guice-persist`) or a custom `UnitOfWork` that calls `em.getTransaction().begin/commit()`. Cleared after each batch flush | V2.0 §12, §17.2 | — |
| 22 | Dual DataSource (Port + Adapter) | **Port:** `DataSourceRouter` interface in `pv-domain/port/datasource/` with `writerDataSource()` and `readerDataSource()` methods. **Adapter:** `DualHikariDataSourceRouter` in `pv-persistence/datasource/` creates two `HikariDataSource` pools (writer → Aurora primary, reader → Aurora replicas). Guice binds via `@Named("writer")` / `@Named("reader")` in `PersistenceModule`. `EntityManagerFactory` wired to writer; read queries use a separate read-only `EntityManager` from the reader pool | V2.0 §3.4, P4 | — |
| 23 | Sequence-based ID generation | `GenerationType.SEQUENCE` with `allocationSize=50` on all entity IDs. This is a JPA annotation — identical to Spring variant. `IDENTITY` columns prohibited. Configured in entity class `@SequenceGenerator` annotations, works with any JPA provider | V2.0 §5.1, P3 | — |

### 2.6 Category 6 — Integration Patterns (5 patterns)

*Outbox, saga, and idempotent consumer are plain Java + SQL — no Spring dependency. Event publishing uses the `DomainEventPublisher` port.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 24 | Transactional Outbox | `trade.outbox` table (aggregate_type, aggregate_id, event_type, JSONB payload, published_at). `OutboxRelayProducer` in `pv-kafka/` polls unpublished rows → publishes to Kafka → marks `published_at` after broker ACK. Domain services write to outbox within the same JPA transaction (via `OutboxRepository` port). No Spring `@TransactionalEventListener` — explicit commit-then-relay ordering | V2.0 §13.4, D-9, FR-106 | — |
| 25 | Saga (choreography) | `trade.saga_state` table tracking multi-step sequences. `SagaRepository` port in `pv-domain`; `JpaSagaRepository` adapter in `pv-persistence`. Saga state transitions are domain logic in `pv-domain/service/`. Stuck sagas (`STARTED` > 30 min) detected by a scheduled check (Guice `ScheduledExecutorService` or external cron) | V2.0 §13.5, FR-001, D-11 | — |
| 26 | Idempotent Consumer | Natural idempotency key: `(trade_id, trade_version)`. `IdempotentConsumer` in `pv-kafka/` checks `volumeSeriesRepository.existsByTradeIdAndTradeVersion()` via the port interface before processing. At-least-once Kafka delivery with idempotent effect (re-derive-from-source, D-7) | V2.0 §13.3, FR-106, D-7 | — |
| 27 | Event-Carried State Transfer | `VolumePublished` and `VolumeSuperseded` carry `series_key + version_id + delivery_range + quality_state`. These are `record` types in `pv-domain/event/` — no framework dependency. Sufficient for downstream routing without fetching event source | V3.0 §8.1–§8.2, FR-052a | `record` for event payloads |
| 28 | CQRS (implicit) | Write path via writer `EntityManager`; read path via reader `EntityManager` (from `DataSourceRouter` port). Slot cache (S6) and trade interval cache (S6b) reads route to reader. Separation enforced by `DataSourceRouter` port, not by framework annotation | D-1, S6, S6b, P4, V2.0 §3.4 | — |

### 2.7 Category 7 — Caching Patterns (3 patterns)

*Cache logic uses a `VolumeCache` port interface in `pv-domain`. Redis implementation lives in `pv-redis`.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 29 | Read-Through | **Port:** `VolumeCache.get(tenantId, seriesKey, intervalStart)` in `pv-domain/port/cache/` — returns `Optional<CachedInterval>`. **Adapter:** `RedisVolumeCache` in `pv-redis/` implements read-through: miss → delegate to `VolumeSeriesRepository` port (which routes to reader DataSource) → populate Redis → return. Redis outage degrades latency, not correctness. `readConsistent=true` parameter bypasses cache | V2.0 §12.6, FR-079 | — |
| 30 | Cache-Aside with Event Invalidation | `VolumeSuperseded` → `VolumeCache.invalidate(seriesKey, affectedRange)`. The domain service calls the port; `RedisVolumeCache` deletes Redis keys by pattern `vol:{tenant_id}:{series_key}:{interval_start_iso}`. Trade amendment → invalidate PROFILE `series_key`. All invalidation logic is event-driven, triggered after transaction commit | V2.0 §12.5, §12.2 | — |
| 31 | Pipeline Batching | `VolumeCache.getAll(tenantId, seriesKey, List<Instant> intervalStarts)` port method. `RedisVolumeCache` translates to Redis `MGET` for bulk fetch (up to 2,976 keys per delivery month). Redis 7 `MULTI-EXEC` for atomic batch writes | FR-079, V2.0 §14.2, SLA §14 | — |

### 2.8 Category 8 — Cross-Cutting Patterns (4 patterns)

*`@TenantAware` annotation lives in `pv-domain`; the interceptor lives in `pv-guice`. `TenantContext` is a port interface.*

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 32 | `@TenantAware` Guice Interceptor | **Port:** `TenantContext` interface in `pv-domain/port/tenant/` — `String currentTenantId()`. **Annotation:** `@TenantAware` in `pv-domain` (a plain `@interface`, no framework dependency). **Interceptor:** Guice `MethodInterceptor` in `pv-guice/TenantModule` — intercepts `@TenantAware`-annotated methods, calls `TenantContext.currentTenantId()`, executes `SET app.tenant_id = :tid` on the JDBC session via `EntityManager.createNativeQuery()`. Bound via `bindInterceptor(Matchers.annotatedWith(TenantAware.class), Matchers.any(), interceptor)`. Three-layer isolation preserved: interceptor (app) → RLS (DB) → index leading key (query). Fail-closed: unset variable matches zero rows | V2.0 §11.1–§11.2, P1, O-2 | — |
| 33 | Functional Interfaces | `Function<VolumeInterval, BigDecimal>` for multiplier application. `BiFunction<PriceExpression, MarketDataSnapshot, BigDecimal>` for price evaluation. Used in stream pipelines for volume resolution and valuation. Pure Java — no framework dependency | D-11, FR-051, V3.0 §2.3 | Functional interfaces + stream API |
| 34 | Immutability Enforcement | PROFILE series: no UPDATE/DELETE after insert — enforced by DB trigger (V2.0 §5.14) AND application-level check in `pv-domain` service layer. METERED_ACTUAL: append-only with `supersedes_id` forward-link. Position ledger: append-only bitemporal versioning. Domain invariants enforced in pure Java; DB triggers are defense-in-depth | P2, FR-050, FR-006, V2.0 §5.14 | `record` types enforce field-level immutability |
| 35 | Bitemporal Audit | `series_history` / `position_history` audit tables. **Guice variant:** JPA `@EntityListeners(BitemporalAuditListener.class)` in `pv-persistence` captures mutations — the listener is a plain class, not a Spring bean. Alternatively, DB-level triggers (V2.0 §10.3) serve as the primary audit mechanism, with the JPA listener as a secondary in-process check. As-of reconstruction via `known_from ≤ K < known_to AND valid_from ≤ B < valid_to` | D-1, FR-006–FR-009, V2.0 §10 | — |

---

## 3. Java 21 Feature Utilization

*Identical to Spring Boot variant — Java 21 features are language-level, not framework-level.*

| # | Java 21 Feature | Application | Code Example |
|---|---|---|---|
| 1 | Records | All value objects: `DeliveryPeriod`, `SeriesKey`, `Money`, `TimeRange`, `DeliveryRange`. Event payloads: `VolumePublished`, `VolumeSuperseded`. Command payloads: `TradeCapture`, `TradeAmend` | `record DeliveryPeriod(ZonedDateTime start, ZonedDateTime end, ZoneId zone) { }` |
| 2 | Sealed interfaces | `PriceExpression` hierarchy (3 leaf types + 10 operator types per FR-048h), `VolumeResolver`, `MaterializationStrategy`. Compiler-enforced exhaustive matching in `switch` — adding a new operator produces compile errors at every unhandled dispatch | `sealed interface PriceExpression permits ConstantLeaf, MarketDataLeaf, IndexLeaf, Add, Subtract, Multiply, Divide, Clamp, Escalate, ConditionalGate, ConditionalPassThrough, TimeAverage, FxConvert { }` |
| 3 | Pattern matching (`switch`) | PriceExpression tree walker: dispatch evaluation per node type (FR-048h). Event routing: dispatch handler per event type. QualityState transitions | `case ConstantLeaf c -> c.value(); case MarketDataLeaf m -> lookupMarketData(m, purpose); case Clamp cl -> clamp(eval(cl.min()), eval(cl.max()), eval(cl.inner()));` |
| 4 | Text blocks | JPQL queries, SQL fragments for complex bitemporal filters, Flyway migration SQL, outbox payload templates | `String jpql = """ SELECT v FROM VolumeSeries v WHERE v.seriesKey = :key AND v.qualityState = 'CURRENT' """;` |
| 5 | Virtual threads | I/O-bound chunk materialization workers — each chunk (one delivery month, ~2,976 intervals) is independent. No Spring thread pool dependency — direct use of `Executors.newVirtualThreadPerTaskExecutor()` | `try (var executor = Executors.newVirtualThreadPerTaskExecutor()) { chunks.forEach(c -> executor.submit(() -> materialize(c))); }` |
| 6 | `SequencedCollection` | Ordered interval lists on `VolumeSeries.intervals` (ordered by `intervalStart`), version chains on audit history (`getFirst()` / `getLast()` for boundary access) | `SequencedSet<VolumeInterval> intervals = new TreeSet<>(Comparator.comparing(VolumeInterval::intervalStart));` |

---

## 3.1 Guice-Specific Wiring Examples

### PersistenceModule

```java
public class PersistenceModule extends AbstractModule {
    @Override
    protected void configure() {
        // Dual DataSource — writer and reader pools
        bind(DataSource.class).annotatedWith(Names.named("writer"))
            .toProvider(WriterDataSourceProvider.class).in(Singleton.class);
        bind(DataSource.class).annotatedWith(Names.named("reader"))
            .toProvider(ReaderDataSourceProvider.class).in(Singleton.class);

        // EntityManagerFactory from writer DataSource
        bind(EntityManagerFactory.class)
            .toProvider(EntityManagerFactoryProvider.class).in(Singleton.class);

        // Repository port → JPA adapter bindings
        bind(VolumeSeriesRepository.class).to(JpaVolumeSeriesRepository.class);
        bind(PositionLedgerRepository.class).to(JpaPositionLedgerRepository.class);
        bind(SettlementCellRepository.class).to(JpaSettlementCellRepository.class);
        bind(OutboxRepository.class).to(JpaOutboxRepository.class);
        bind(SagaRepository.class).to(JpaSagaRepository.class);

        // DataSourceRouter port → dual adapter
        bind(DataSourceRouter.class).to(DualHikariDataSourceRouter.class);
    }
}
```

### TenantModule

```java
public class TenantModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(TenantContext.class).to(ThreadLocalTenantContext.class);

        TenantInterceptor interceptor = new TenantInterceptor();
        requestInjection(interceptor);
        bindInterceptor(
            Matchers.any(),
            Matchers.annotatedWith(TenantAware.class),
            interceptor
        );
    }
}

// The interceptor — lives in pv-guice, not pv-domain
public class TenantInterceptor implements MethodInterceptor {
    @Inject Provider<EntityManager> emProvider;
    @Inject TenantContext tenantContext;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        EntityManager em = emProvider.get();
        em.createNativeQuery("SET app.tenant_id = :tid")
          .setParameter("tid", tenantContext.currentTenantId())
          .executeUpdate();
        return invocation.proceed();
    }
}
```

### EventModule

```java
public class EventModule extends AbstractModule {
    @Override
    protected void configure() {
        // For unit tests and in-process use: synchronous event bus
        bind(DomainEventPublisher.class).to(InProcessEventPublisher.class);

        // For production: swap to Kafka-backed publisher
        // bind(DomainEventPublisher.class).to(KafkaEventPublisher.class);
    }
}
```

### DomainModule

```java
public class DomainModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(VolumeSeriesFactory.class).in(Singleton.class);
        bind(PriceEvaluator.class).to(CompositePriceEvaluator.class);
        bind(MaterializationStrategy.class).to(RollingHorizonStrategy.class);
    }
}
```

### Application Bootstrap

```java
public class PositionValuationApp {
    public static void main(String[] args) {
        Injector injector = Guice.createInjector(
            new DomainModule(),
            new PersistenceModule(),
            new TenantModule(),
            new EventModule(),
            new CacheModule()
        );
        // Application ready — all ports bound to adapters
    }
}
```

---

## 4. Summary Table

All 35 patterns with module location:

| # | Pattern | Category | Subsystem(s) | Module | Spec Reference | Java 21 Feature |
|---|---|---|---|---|---|---|
| 1 | Entity vs Measure distinction | Domain Model | S1, S5a, S5b, S6 | `pv-domain` | D-1, D-8, FR-001–FR-003 | — |
| 2 | Aggregate Root | Domain Model | S3, S1 | `pv-domain` | V3.0 §3.1, FR-050 | `SequencedCollection` |
| 3 | Value Object (record) | Domain Model | S1, S2, S3 | `pv-domain` | FR-001, FR-036, D-2 | `record` |
| 4 | Enum with behavior | Domain Model | S3, S5a | `pv-domain` | FR-054, V3.0 §3.2.6 | Enhanced `enum` |
| 5 | Sealed type hierarchy | Domain Model | S2 | `pv-domain` | D-2, FR-040, FR-048–FR-048h | `sealed interface` |
| 6 | Builder | Creational | S1, S3 | `pv-domain` | D-1, D-11, FR-030 | — |
| 7 | Factory Method | Creational | S3 | `pv-domain` | D-11, FR-050 | — |
| 8 | Static Factory | Creational | S1, S2 | `pv-domain` | D-2, FR-036 | `record` static factory |
| 9 | Strategy — Volume Resolution | Structural | S3, S6b | `pv-domain` | D-11, FR-050–FR-051 | `sealed interface` |
| 10 | Strategy — Price Evaluation | Structural | S2, S5a | `pv-domain` | D-2, FR-020, FR-040, FR-048 | Pattern matching `switch` |
| 11 | Strategy — Materialization | Structural | S3, S6b | `pv-domain` | FR-056, V3.0 §4 | — |
| 12 | Composite | Structural | S2 | `pv-domain` | D-2, FR-025, FR-042, FR-048d–g | Pattern matching |
| 13 | Decorator / Filter Chain | Structural | Cross-cutting | `pv-guice` | O-2, V2.0 §11 | — |
| 14 | Observer / Domain Events | Behavioral | S3, S5a, S5b, S8 | `pv-domain` (port) + `pv-kafka` (adapter) | V3.0 §8, FR-052a–c | `record` |
| 15 | Template Method | Behavioral | S5a, S5b, S6, S6b | `pv-domain` | FR-056 | — |
| 16 | State Machine | Behavioral | S3 | `pv-domain` | FR-054, V3.0 §3.2.6 | Enum methods |
| 17 | Command | Behavioral | S1, S3 | `pv-domain` | FR-001–FR-005, FR-037 | `record` |
| 18 | Repository (Port + Adapter) | Persistence | S1, S3, S5a | `pv-domain` (port) + `pv-persistence` (adapter) | V2.0 §5 | — |
| 19 | Specification (query) | Persistence | S3 | `pv-domain` (port) + `pv-persistence` (adapter) | FR-051, V2.0 §7 | Functional interfaces |
| 20 | Unit of Work (batch flush) | Persistence | S3 | `pv-persistence` | V2.0 §17.1 | — |
| 21 | Identity Map | Persistence | S3 | `pv-persistence` | V2.0 §12 | — |
| 22 | Dual DataSource (Port + Adapter) | Persistence | S1, S3, S5a, S6 | `pv-domain` (port) + `pv-persistence` (adapter) | V2.0 §3.4, P4 | — |
| 23 | Sequence-based ID | Persistence | S1, S3 | `pv-persistence` | V2.0 §5.1, P3 | — |
| 24 | Transactional Outbox | Integration | S3, S8 | `pv-domain` (port) + `pv-kafka` (relay) | V2.0 §13.4, D-9 | — |
| 25 | Saga (choreography) | Integration | S1, S3 | `pv-domain` (port + logic) + `pv-persistence` (adapter) | V2.0 §13.5, FR-001 | — |
| 26 | Idempotent Consumer | Integration | S3 | `pv-kafka` | V2.0 §13.3, D-7 | — |
| 27 | Event-Carried State Transfer | Integration | S3, S5a, S5b | `pv-domain` | V3.0 §8, FR-052a | `record` |
| 28 | CQRS (implicit) | Integration | S1, S6, S6b | `pv-domain` (port) + `pv-persistence` (adapter) | D-1, P4 | — |
| 29 | Read-Through | Caching | S3, S6 | `pv-domain` (port) + `pv-redis` (adapter) | V2.0 §12.6, FR-079 | — |
| 30 | Cache-Aside + Event Invalidation | Caching | S3, S6 | `pv-domain` (port) + `pv-redis` (adapter) | V2.0 §12.5 | — |
| 31 | Pipeline Batching | Caching | S6, S6b | `pv-domain` (port) + `pv-redis` (adapter) | FR-079, V2.0 §14 | — |
| 32 | `@TenantAware` Interceptor | Cross-Cutting | All (S1–S8) | `pv-domain` (annotation + port) + `pv-guice` (interceptor) | V2.0 §11, P1 | — |
| 33 | Functional Interfaces | Cross-Cutting | S3, S5a | `pv-domain` | D-11, FR-051 | Functional interfaces |
| 34 | Immutability Enforcement | Cross-Cutting | S1, S3 | `pv-domain` + `pv-persistence` (DB triggers) | P2, FR-050 | `record` |
| 35 | Bitemporal Audit | Cross-Cutting | S1, S3, S5a | `pv-persistence` (JPA listener) + DB triggers | D-1, FR-006–FR-009, V2.0 §10 | — |

---

## 5. Consequences

### Positive

- **Framework independence.** `pv-domain` has zero runtime dependencies on Guice, Spring, or any DI container. Domain logic is testable with plain JUnit + mock implementations of port interfaces. This is the primary advantage over the Spring Boot variant.
- **Portability.** The same `pv-domain` jar can be used with Guice (`pv-guice`), Spring Boot (`pv-spring-boot`), or any other DI container. Only the wiring module changes.
- **Type-safe domain model.** Sealed interfaces + pattern matching `switch` enforce exhaustive handling of `PriceExpression` variants at compile time (identical to Spring variant).
- **Immutability by default.** Java `record` types for value objects and event payloads (identical to Spring variant).
- **Testable strategies.** Each strategy is independently testable with stub port implementations — no Spring context needed, no Guice injector needed for unit tests.
- **Explicit wiring.** Guice modules make every binding visible in one place. No classpath scanning, no auto-configuration magic. Easier to reason about what is bound where.
- **Lightweight startup.** Guice injector creation is ~10-50ms vs ~2-5s for a Spring Boot context. Relevant for integration tests and CLI tools.
- **Clear layering.** The Entity → Measure → Cache dependency chain is reinforced by the module structure: `pv-domain` → `pv-persistence` → `pv-guice`.
- **Regulatory compliance and unified volume resolution.** Identical to Spring variant — these are domain concerns, not framework concerns.

### Negative

- **No Spring Data JPA.** Repositories are hand-written against `EntityManager`. More boilerplate for CRUD operations (findById, save, delete). Mitigated by keeping repository implementations thin — most logic is in domain services.
- **No `@Transactional` annotation (Spring-style).** Transaction boundaries require either `guice-persist` (which provides `@Transactional` via Guice AOP) or explicit `EntityManager.getTransaction().begin()/commit()` in a `UnitOfWork` helper. The explicit approach is more transparent but more verbose.
- **No Spring Boot auto-configuration.** DataSource, EntityManagerFactory, Kafka consumers, Redis connections — all must be configured explicitly in Guice modules. More initial setup, but no hidden defaults to debug.
- **Smaller ecosystem.** Spring Boot has broader community support, more third-party integrations, and more hiring pool familiarity. Guice is mature but less commonly used for web applications.
- **Learning curve.** Developers must understand both hexagonal architecture (ports/adapters) and Guice's binding model. The pattern itself is well-documented but unfamiliar to Spring-only teams.
- **Hibernate compatibility.** Same as Spring variant — records cannot be JPA entities; entities remain traditional classes.

### Neutral

- **Pattern count.** 35 patterns — identical to Spring variant. The domain patterns are framework-independent; only the wiring differs.
- **Virtual threads.** More natural in the library variant — no Spring thread pool abstraction in the way. Direct use of `Executors.newVirtualThreadPerTaskExecutor()`.
- **JSR-330 annotations.** `@Inject` and `@Named` (from `jakarta.inject`) work with both Guice and Spring. Using JSR-330 in `pv-persistence` adapters keeps them portable.

---

## 5.1 Spring Boot Variant vs Library + Guice — Key Differences

| Concern | Spring Boot (`-springboot`) | Library + Guice (`-library_guice`) |
|---|---|---|
| Domain code location | Mixed with Spring annotations | Isolated in `pv-domain`, zero framework deps |
| Repository pattern | Spring Data JPA interfaces (generated impls) | Port interface in `pv-domain` + hand-written JPA impl in `pv-persistence` |
| Transaction management | `@Transactional` annotation (Spring TX) | `guice-persist` `@Transactional` or explicit `UnitOfWork` |
| AOP / tenant isolation | Spring AOP `@Aspect` | Guice `MethodInterceptor` + `bindInterceptor()` |
| Event publishing | `ApplicationEventPublisher` | `DomainEventPublisher` port → in-process or Kafka adapter |
| DataSource routing | Spring `AbstractRoutingDataSource` or bean-level | Guice `@Named("writer")`/`@Named("reader")` bindings |
| Configuration | `application.yml` + `@ConfigurationProperties` | Guice `Names.named()` bindings or injected `Config` record |
| Startup time | ~2-5s (Spring context) | ~10-50ms (Guice injector) |
| Test isolation | `@SpringBootTest` or `@DataJpaTest` (heavy) | Plain JUnit + mock ports (lightweight) |
| Portability | Tied to Spring ecosystem | `pv-domain` reusable with any framework |

---

## 6. Compliance Matrix

*Identical to Spring Boot variant — compliance is a domain concern, not a framework concern.*

### 6.1 Design Decisions → Patterns

| Decision | Description | Implementing Patterns |
|---|---|---|
| D-1 | Ledger grain = trade-leg × delivery-month block; signed qty; no interval fan-out in S1 | #1 Entity vs Measure, #2 Aggregate Root, #6 Builder, #34 Immutability, #35 Bitemporal Audit |
| D-2 | Price = expression ref; fixed price = degenerate expression (FR-048: full taxonomy from ConstantLeaf to multi-index PPA) | #3 Value Object, #5 Sealed Hierarchy, #8 Static Factory, #10 Strategy (Price), #12 Composite |
| D-3 | Forward marks ephemeral; settlement bitemporal; EOD strike = month-bucket with stamps | #1 Entity vs Measure, #15 Template Method, #28 CQRS |
| D-4 | Optimized version-binding; `active_leaves` captured at first resolution | #12 Composite, #15 Template Method |
| D-5 | Peak is interval-dimension data, calendar-versioned; never on position rows | #1 Entity vs Measure, #33 Functional Interfaces |
| D-6 | Dual units (MW+MWh) materialized only in cache/rollups; single canonical in ledger | #1 Entity vs Measure, #29 Read-Through, #31 Pipeline Batching |
| D-7 | Batch authoritative, events additive; re-derive-not-delta idempotency | #26 Idempotent Consumer, #15 Template Method, #14 Observer |
| D-8 | Entity/measure distinction; netting is projection policy | #1 Entity vs Measure, #28 CQRS |
| D-9 | Shared monthly partitions with tenant as leading key; S5c on strike-month axis | #24 Transactional Outbox, #32 @TenantAware Interceptor, #23 Sequence-based ID |
| D-10 | All interval structure via MarketCalendar; no timestamp arithmetic | #3 Value Object (DeliveryPeriod), #33 Functional Interfaces |
| D-11 | Unified volume resolution: VolumeReference → VolumeSeries × multiplier | #9 Strategy (Volume), #7 Factory Method, #33 Functional Interfaces, #27 Event-Carried State Transfer |
| D-12 | S6b trade_interval_cache: optional, rebuildable, event-driven | #11 Strategy (Materialization), #30 Cache-Aside, #15 Template Method |

### 6.2 Subsystem Coverage

| Subsystem | Description | Patterns |
|---|---|---|
| S1 | Position Ledger | #1, #2, #6, #17, #18, #22, #23, #25, #34, #35 |
| S2 | PriceExpression | #3, #5, #8, #10, #12 |
| S3 | VolumeSeries (V3.0 unified) | #2, #4, #7, #9, #14, #16, #18, #19, #20, #21, #23, #24, #27, #30, #34, #35 |
| S4 | Market Data Store | #10, #27 |
| S5a | Settlement cells | #1, #4, #14, #15, #18, #22, #35 |
| S5b | Forward marks | #1, #14, #15, #27 |
| S5c | EOD struck marks | #1, #15, #28 |
| S6 | Slot Cache | #1, #22, #28, #29, #30, #31 |
| S6b | Trade Interval Cache | #9, #11, #15, #28, #30, #31 |
| S7 | Rollups | #15, #33 |
| S8 | Dependency index | #14, #24 |

### 6.3 Key FR Numbers → Patterns

| FR Range | Topic | Patterns |
|---|---|---|
| FR-001–FR-003 | Entity vs Measure fundamentals | #1 |
| FR-004–FR-005 | Grain follows lifecycle of change | #1, #2 |
| FR-006–FR-009 | Bitemporality | #35 |
| FR-020–FR-025 | Market, DeliveryPoint, MarketCalendar | #3, #5, #10, #12 |
| FR-030–FR-039 | Position Ledger attributes & lifecycle | #6, #17, #34 |
| FR-040 | PriceExpression reference | #5, #10, #12 |
| FR-048–FR-048h | PriceExpression taxonomy, leaf/operator types, active_leaves | #5, #10, #12 |
| FR-050–FR-056 | Volume series interface & resolution | #2, #7, #9, #11, #15, #19 |
| FR-054 | QualityState transitions | #4, #16 |
| FR-075 | Forward marks ephemeral | #1, #28 |
| FR-079 | EOD struck marks & cache reads | #29, #31 |
| FR-106 | Idempotent Kafka processing | #26 |
| FR-120/FR-122 | Multitenancy | #32 |

---

## 7. References

### Specification Documents

| Document | Path | Version |
|---|---|---|
| Functional Specification | `docs/functional-spec/functional-spec-position-valuation-v1.0.md` | 1.0 |
| Volume Series Domain Model | `docs/functional-spec/VOLUME_SERIES_SPEC-V3_0.md` | 3.0 |
| Data Architecture | `docs/technical-spec/VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` | 2.0 |
| Design Context & Rationale | `docs/context/CONTEXT-position-valuation-design.md` | — |
| Plain Language Guide | `docs/spec-in-layman-language/README.md` | — |
| Spring Boot Variant | `docs/technical-spec/ADR-001-IMPLEMENTATION-PATTERN-CATALOG-springboot.md` | — |

### Java 21 JEP References

| JEP | Feature | Patterns Using It |
|---|---|---|
| JEP 395 | Records | #3, #8, #14, #17, #27, #34 |
| JEP 409 | Sealed Classes | #5, #9, #10 |
| JEP 441 | Pattern Matching for `switch` | #5, #10, #12 |
| JEP 378 | Text Blocks | JPQL queries, SQL fragments |
| JEP 444 | Virtual Threads | #11 |
| JEP 431 | Sequenced Collections | #2 |

### Platform Versions

| Component | Version | Constraint Source |
|---|---|---|
| Java | 21 (LTS) | V2.0 §1.4, Context §11 |
| Google Guice | 7 | This ADR |
| JPA / Hibernate | 7.x (Jakarta EE 11, JPA 3.2) | This ADR |
| Aurora PostgreSQL | 16 | V2.0 §1.4, P0 |
| Kafka | 3.7 (KRaft) | V2.0 §1.4 |
| Redis | 7 (Jedis or Lettuce) | V2.0 §1.4 |
| pg_partman + pg_cron | — | V2.0 §6, P6 |
| Flyway | — | V2.0 §16.1 |
| HikariCP + PgBouncer | — | V2.0 §3.4, P4 |
| Spring Boot (optional adapter) | 4.0.7 | V2.0 §1.4 (only if `pv-spring-boot` module used) |
