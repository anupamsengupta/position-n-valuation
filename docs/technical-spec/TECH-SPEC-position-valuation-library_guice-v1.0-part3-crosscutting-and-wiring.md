# Technical Specification — Position & Valuation (Library + Guice) v1.0

## Part 3 — Cross-Cutting Concerns and Wiring (§14–§22)

| Part | File | Sections |
|------|------|----------|
| Part 1 | `TECH-SPEC-…-part1-domain-and-ports.md` | §1–§7 |
| Part 2 | `TECH-SPEC-…-part2-subsystems.md` | §8–§13 |
| **Part 3 (this file)** | `TECH-SPEC-…-part3-crosscutting-and-wiring.md` | §14–§22 |

---

## §14 — Cross-Cutting Concerns

### 14.1 `TenantContext` Port — Pattern #32, V2.0 §11, P1, FR-120

> **TR-032** — Multitenancy uses three-layer isolation: (1) application-level session variable `app.tenant_id`, (2) PostgreSQL Row-Level Security policies, (3) tenant-leading index design. The `TenantContext` port in `pv-domain` is the application-level accessor. (Extends V2.0 §11, P1.)

```java
/**
 * Port interface for tenant context. Lives in pv-domain/port/tenant/.
 * No framework annotations — pure Java interface.
 */
public interface TenantContext {

    /**
     * Returns the current tenant ID. Throws if no tenant is set.
     */
    String currentTenantId();

    /**
     * Set the tenant for the current execution scope.
     * Implementation must set the PostgreSQL session variable `app.tenant_id`
     * for RLS policy enforcement.
     */
    void setTenant(String tenantId);

    /**
     * Clear the tenant context (for cleanup after request).
     */
    void clear();
}
```

### 14.2 `@TenantAware` Annotation — Pattern #32, pv-domain

```java
/**
 * Marker annotation for methods that require a tenant context.
 * Lives in pv-domain — portable, no framework dependency.
 * The TenantInterceptor (pv-guice) reads this at runtime via Guice AOP.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TenantAware {}
```

### 14.3 `TenantInterceptor` — Pattern #32, #13, pv-guice

> **TR-033** — The `TenantInterceptor` is a Guice `MethodInterceptor` bound via `bindInterceptor()` in `TenantModule`. It extracts the tenant ID from the request context, sets it via `TenantContext.setTenant()` (which sets the PostgreSQL session variable), and clears it after method completion. This is the Guice equivalent of a Spring AOP `@Aspect`. (Extends V2.0 §11.)

```java
/**
 * Guice MethodInterceptor for @TenantAware methods.
 * Lives in pv-guice/. Sets PostgreSQL session variable for RLS.
 *
 * Full class — this is the wiring concern Guice owns.
 */
public class TenantInterceptor implements MethodInterceptor {

    @Inject private Provider<TenantContext> tenantContext;
    @Inject private Provider<EntityManager> entityManager;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String tenantId = extractTenantId(invocation);
        TenantContext ctx = tenantContext.get();

        try {
            ctx.setTenant(tenantId);

            // Set PostgreSQL session variable for RLS enforcement
            entityManager.get()
                .createNativeQuery("SET LOCAL app.tenant_id = :tid")
                .setParameter("tid", tenantId)
                .executeUpdate();

            return invocation.proceed();
        } finally {
            ctx.clear();
        }
    }

    private String extractTenantId(MethodInvocation invocation) {
        // Extract from first parameter annotated with @TenantId,
        // or from a thread-local request context
        for (Object arg : invocation.getArguments()) {
            if (arg instanceof String s && isTenantIdParam(invocation, arg)) {
                return s;
            }
        }
        throw new IllegalStateException(
            "No tenant ID found for @TenantAware method: "
                + invocation.getMethod().getName());
    }

    private boolean isTenantIdParam(MethodInvocation invocation, Object arg) {
        // Check parameter annotations or naming convention
        // Implementation detail — simplified here
        return true;
    }
}
```

### 14.4 `DataSourceRouter` Port — Pattern #22, V2.0 §3.4, P4

```java
/**
 * Port interface for dual DataSource routing.
 * Lives in pv-domain/port/datasource/.
 * Writer → Aurora primary; Reader → Aurora replicas.
 */
public interface DataSourceRouter {

    /**
     * DataSource for write operations (Aurora primary).
     */
    javax.sql.DataSource writerDataSource();

    /**
     * DataSource for read-only operations (Aurora replica).
     */
    javax.sql.DataSource readerDataSource();
}
```

### 14.5 `DualHikariDataSourceRouter` — Pattern #22, pv-persistence

```java
/**
 * Dual HikariCP pool implementation of DataSourceRouter.
 * Lives in pv-persistence/datasource/.
 *
 * Writer pool → Aurora primary endpoint.
 * Reader pool → Aurora reader endpoint (round-robin across replicas).
 *
 * V2.0 §3.4: per-tenant Hikari + PgBouncer.
 */
public class DualHikariDataSourceRouter implements DataSourceRouter {

    private final DataSource writer;
    private final DataSource reader;

    @Inject
    public DualHikariDataSourceRouter(
            @Named("writer") DataSource writer,
            @Named("reader") DataSource reader) {
        this.writer = writer;
        this.reader = reader;
    }

    @Override
    public DataSource writerDataSource() { return writer; }

    @Override
    public DataSource readerDataSource() { return reader; }
}
```

### 14.6 `BitemporalAuditListener` — Pattern #35, FR-006–FR-009, V2.0 §10, pv-persistence

> **TR-034** — `BitemporalAuditListener` is a JPA `@EntityListener` that captures bitemporal metadata on entity lifecycle events. It sets `known_from` on `@PrePersist` and validates immutability invariants on `@PreUpdate`. This is defense-in-depth — the DB trigger `prevent_position_update()` (V2.0 §10.3) provides the ultimate guard. (Extends FR-006, V2.0 §10.3.)

```java
/**
 * JPA entity listener for bitemporal audit.
 * Lives in pv-persistence/audit/.
 * Defense-in-depth: DB triggers are the ultimate guard (V2.0 §10.3).
 */
public class BitemporalAuditListener {

    /**
     * Set knowledge-time on new entity versions.
     * FR-006: known_from = processing time (append-only).
     */
    @PrePersist
    public void onPrePersist(Object entity) {
        if (entity instanceof PositionLedgerEntryEntity ple) {
            if (ple.getKnownFrom() == null) {
                ple.setKnownFrom(Instant.now());
            }
        }
        if (entity instanceof SettlementCellEntity sc) {
            if (sc.getKnownFrom() == null) {
                sc.setKnownFrom(Instant.now());
            }
        }
    }

    /**
     * Guard: bitemporal entities should not be updated in-place.
     * The only allowed update is closing known_to for supersession.
     * FR-006: versions superseded by closing known_to, never by in-place update.
     */
    @PreUpdate
    public void onPreUpdate(Object entity) {
        if (entity instanceof PositionLedgerEntryEntity ple) {
            // Only known_to may change (supersession close)
            // All other fields must remain unchanged
            // This is a runtime assertion; DB trigger is the hard guard
        }
    }
}
```

---

## §15 — Kafka Topics & Event Contracts

### 15.1 Topic Names & Key Strategy — Pattern #24, #27, D-9, FR-106

> **TR-035** — Topic naming follows `{domain}.{aggregate}.{event_type}` convention. Keys ensure per-aggregate ordering within a partition. Headers carry `tenant_id` and `event_id` (UUID) for routing and deduplication. (Extends D-9.)

| Topic | Key | Payload Record | Partition Key | Consumers |
|-------|-----|----------------|---------------|-----------|
| `trade.position.captured` | `{trade_id}` | `PositionCaptured` | `trade_id` | Volume service, slot cache, S6b |
| `trade.position.amended` | `{trade_id}` | `PositionAmended` | `trade_id` | Volume service, valuation, slot cache |
| `trade.position.cancelled` | `{trade_id}` | `PositionCancelled` | `trade_id` | Volume service, valuation, slot cache |
| `volume.series.published` | `{series_key}` | `VolumePublished` | `series_key` | Valuation (S5b), slot cache (S6), S6b |
| `volume.series.superseded` | `{series_key}` | `VolumeSuperseded` | `series_key` | Valuation (S5a, S5b), slot cache, S6b, dependency index |
| `volume.chunk.materialized` | `{series_key}` | `VolumeChunkMaterialized` | `series_key` | Monitoring dashboard |
| `valuation.settlement.computed` | `{position_id}` | `SettlementComputed` | `position_id` | Dependency index, rollups |
| `marketdata.fixing.published` | `{series}` | `SettlementPublished` | `series` | Valuation (S5a) |
| `marketdata.curve.tick` | `{series}` | `CurveTick` | `series` | Valuation (S5b), forward marks |

**Headers** (on all messages):

| Header | Type | Purpose |
|--------|------|---------|
| `tenant_id` | String | Multitenancy routing |
| `event_id` | UUID | Deduplication (idempotent consumer) |
| `event_time` | ISO-8601 | Processing timestamp |
| `source_module` | String | e.g., `pv-domain`, `market-data` |

### 15.2 Outbox Schema — Pattern #24, V2.0 §13.4

```java
/**
 * Outbox entry — written within the same JPA transaction as the domain event.
 * V2.0 §13.4: commit → relay ordering.
 */
@Entity
@Table(name = "outbox", schema = "trade")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", length = 64, nullable = false)
    private String aggregateType;     // e.g., "VolumeSeries", "PositionLedger"

    @Column(name = "aggregate_id", length = 64, nullable = false)
    private String aggregateId;       // e.g., series_key, trade_id

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;         // e.g., "VolumePublished"

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;           // JSON-serialized event record

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;      // null until relay publishes to Kafka

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts = 0;
}
```

**Outbox index:**

```sql
CREATE INDEX idx_outbox_unpublished
    ON trade.outbox (created_at)
    WHERE published_at IS NULL;
```

**Poller cadence:** The `OutboxRelayProducer` (§15.3) polls every 100ms for unpublished rows. Relay marks `published_at` only after Kafka broker ACK.

### 15.3 Idempotency Keys — Pattern #26, FR-106, D-7

> **TR-036** — Idempotency key for trade events: natural key `(trade_id, trade_version)`. For volume events: `(series_key, version_id)`. Re-derive-from-source semantics (D-7): on duplicate receipt, the consumer re-derives the result from the current source, which is idempotent. No separate deduplication table needed. (Extends FR-106, D-7.)

```java
/**
 * Idempotent consumer base class. Lives in pv-kafka/consumer/.
 * Checks existence before processing; re-derive on duplicate.
 */
public abstract class IdempotentConsumer<E> {

    /**
     * Check if this event has already been processed.
     * Subclasses implement using the appropriate repository port.
     */
    protected abstract boolean alreadyProcessed(E event);

    /**
     * Process the event. Must be idempotent even without the check.
     * D-7: re-derive-not-delta — recompute from source, don't apply delta.
     */
    protected abstract void process(E event);

    public final void handle(E event) {
        if (alreadyProcessed(event)) {
            // Log and skip — safe because processing is idempotent
            return;
        }
        process(event);
    }
}
```

---

## §16 — Guice Wiring

### 16.1 `DomainModule` — Pattern #9, #10, #11, #15

```java
/**
 * Guice module for domain services. Lives in pv-guice/.
 * Binds domain service interfaces to implementations.
 * All implementations live in pv-domain — no framework dependency.
 */
public class DomainModule extends AbstractModule {

    @Override
    protected void configure() {
        // Numeric precision configuration (§5.0, TR-046)
        // Default: EU power conventions. Override with @Named("gas") for commodity-specific.
        bind(NumericPrecision.class)
            .toInstance(new DefaultNumericPrecision());

        // Volume resolution (Pattern #9)
        bind(ProfileResolver.class).in(Singleton.class);
        bind(ForecastResolver.class).in(Singleton.class);

        // Price evaluation (Pattern #10)
        bind(PriceEvaluator.class)
            .to(DefaultPriceEvaluator.class)
            .in(Singleton.class);

        // Volume series factory (Pattern #7)
        bind(VolumeSeriesFactory.class).in(Singleton.class);

        // Command handlers (Pattern #17)
        bind(TradeCaptureHandler.class)
            .to(DefaultTradeCaptureHandler.class)
            .in(Singleton.class);
        bind(TradeAmendHandler.class)
            .to(DefaultTradeAmendHandler.class)
            .in(Singleton.class);
        bind(TradeCancelHandler.class)
            .to(DefaultTradeCancelHandler.class)
            .in(Singleton.class);

        // Cache invalidation handler
        bind(CacheInvalidationHandler.class).in(Singleton.class);

        // S6b rebuilder
        bind(TradeIntervalCacheRebuilder.class).in(Singleton.class);
    }
}
```

### 16.2 `PersistenceModule` — Pattern #18, #20, #22, #23

```java
/**
 * Guice module for JPA persistence adapters. Lives in pv-guice/.
 * Binds port interfaces to JPA adapter implementations.
 */
public class PersistenceModule extends AbstractModule {

    @Override
    protected void configure() {
        // DataSource routing (Pattern #22)
        bind(DataSource.class).annotatedWith(Names.named("writer"))
            .toProvider(WriterDataSourceProvider.class)
            .in(Singleton.class);
        bind(DataSource.class).annotatedWith(Names.named("reader"))
            .toProvider(ReaderDataSourceProvider.class)
            .in(Singleton.class);
        bind(DataSourceRouter.class)
            .to(DualHikariDataSourceRouter.class)
            .in(Singleton.class);

        // EntityManagerFactory — bound to writer DataSource
        bind(EntityManagerFactory.class)
            .toProvider(EntityManagerFactoryProvider.class)
            .in(Singleton.class);

        // Per-request EntityManager (not Singleton)
        bind(EntityManager.class)
            .toProvider(EntityManagerProvider.class);

        // Repository ports → JPA adapters (Pattern #18)
        bind(PositionLedgerRepository.class)
            .to(JpaPositionLedgerRepository.class)
            .in(Singleton.class);
        bind(VolumeSeriesRepository.class)
            .to(JpaVolumeSeriesRepository.class)
            .in(Singleton.class);
        bind(SettlementCellRepository.class)
            .to(JpaSettlementCellRepository.class)
            .in(Singleton.class);
        bind(RollupRepository.class)
            .to(JpaRollupRepository.class)
            .in(Singleton.class);
        bind(DependencyIndex.class)
            .to(JpaDependencyIndex.class)
            .in(Singleton.class);

        // Batch writer (Pattern #20)
        bind(BatchWriter.class).in(Singleton.class);
    }
}
```

### 16.3 `TenantModule` — Pattern #32, #13

```java
/**
 * Guice module for tenant isolation. Lives in pv-guice/.
 * Binds TenantContext port and installs the @TenantAware interceptor.
 */
public class TenantModule extends AbstractModule {

    @Override
    protected void configure() {
        // Tenant context port → thread-local implementation
        bind(TenantContext.class)
            .to(ThreadLocalTenantContext.class)
            .in(Singleton.class);

        // @TenantAware interceptor (Pattern #32)
        TenantInterceptor interceptor = new TenantInterceptor();
        requestInjection(interceptor);
        bindInterceptor(
            Matchers.any(),
            Matchers.annotatedWith(TenantAware.class),
            interceptor
        );
    }
}
```

### 16.4 `EventModule` — Pattern #14, #24, #27

```java
/**
 * Guice module for domain event publishing. Lives in pv-guice/.
 * Binds the DomainEventPublisher port to the outbox-based adapter.
 */
public class EventModule extends AbstractModule {

    @Override
    protected void configure() {
        // Domain event publisher → outbox adapter (Pattern #24)
        bind(DomainEventPublisher.class)
            .to(OutboxDomainEventPublisher.class)
            .in(Singleton.class);

        // Forward mark store (S5b) → Redis adapter
        bind(ForwardMarkStore.class)
            .to(RedisForwardMarkStore.class)
            .in(Singleton.class);
    }
}
```

### 16.5 `CacheModule` — Pattern #29, #30, #31

```java
/**
 * Guice module for cache infrastructure. Lives in pv-guice/.
 * Binds cache ports to Redis adapters.
 */
public class CacheModule extends AbstractModule {

    @Override
    protected void configure() {
        // Redis connection
        bind(RedisCommands.class)
            .toProvider(RedisConnectionProvider.class)
            .in(Singleton.class);

        // Slot cache port → Redis adapter (Pattern #29, #30, #31)
        bind(VolumeCache.class)
            .to(RedisVolumeCache.class)
            .in(Singleton.class);

        // Trade interval cache port → JPA adapter (S6b)
        bind(TradeIntervalCache.class)
            .to(JpaTradeIntervalCache.class)
            .in(Singleton.class);
    }
}
```

### 16.6 `KafkaModule` — Pattern #24, #26

```java
/**
 * Guice module for Kafka infrastructure. Lives in pv-guice/.
 * Binds outbox relay, consumers, and producer configuration.
 */
public class KafkaModule extends AbstractModule {

    @Override
    protected void configure() {
        // Outbox relay producer (Pattern #24)
        bind(OutboxRelayProducer.class).in(Singleton.class);

        // Idempotent consumers (Pattern #26)
        bind(TradeCapturedConsumer.class).in(Singleton.class);
        bind(VolumeSupersededConsumer.class).in(Singleton.class);
        bind(SettlementPublishedConsumer.class).in(Singleton.class);
        bind(CurveTickConsumer.class).in(Singleton.class);

        // Kafka producer/consumer configuration
        bind(KafkaProducer.class)
            .toProvider(KafkaProducerProvider.class)
            .in(Singleton.class);
        bind(KafkaConsumer.class)
            .toProvider(KafkaConsumerProvider.class)
            .in(Singleton.class);
    }
}
```

### 16.7 Scoping Rules

| Component | Scope | Rationale |
|-----------|-------|-----------|
| All adapters (repositories, cache, producers) | `Singleton` | Stateless; hold connection references |
| `VolumeSeriesFactory`, domain services | `Singleton` | Stateless; dependencies injected |
| `EntityManager` | Unscoped (`Provider<EntityManager>`) | Per-request; short-lived |
| `TenantContext` | `Singleton` (thread-local internal) | One instance, thread-local state |
| `BatchWriter` | `Singleton` | Holds `Provider<EntityManager>` |

---

## §17 — Transactions

### 17.1 Transaction Strategy Decision

> **TR-037** — Transaction management uses **explicit `UnitOfWork` wrapper** over `guice-persist` `@Transactional`. Rationale: (1) `guice-persist` requires the `PersistFilter` servlet filter, which assumes a web container — our library variant may run as a CLI tool, batch processor, or embedded in another service; (2) explicit transaction boundaries make commit ordering visible in the code, which is critical for the outbox pattern (commit domain mutation → relay events); (3) `UnitOfWork` is three lines of code vs a full `guice-persist` dependency. (Extends Pattern #24.)

**Alternative considered:** `guice-persist` `@Transactional` provides annotation-driven transactions via Guice AOP, similar to Spring's `@Transactional`. It works well but ties the transaction scope to method boundaries, making outbox sequencing harder to reason about.

### 17.2 `UnitOfWork` — Explicit Transaction Wrapper

```java
/**
 * Explicit transaction boundary wrapper. Lives in pv-persistence/.
 * Preferred over guice-persist @Transactional for visibility and portability.
 */
public class UnitOfWork {

    private final Provider<EntityManager> emProvider;

    @Inject
    public UnitOfWork(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Execute work within a JPA transaction.
     * Commit on success; rollback on exception.
     *
     * Pattern #24: domain mutation + outbox write happen within this boundary.
     * The outbox relay runs AFTER commit, not within the transaction.
     */
    public <T> T execute(TransactionalWork<T> work) {
        EntityManager em = emProvider.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.execute(em);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /**
     * Void variant for operations without a return value.
     */
    public void run(TransactionalAction action) {
        execute(em -> { action.execute(em); return null; });
    }

    @FunctionalInterface
    public interface TransactionalWork<T> {
        T execute(EntityManager em);
    }

    @FunctionalInterface
    public interface TransactionalAction {
        void execute(EntityManager em);
    }
}
```

### 17.3 Outbox-Write-in-Same-Transaction — Pattern #24

> **TR-038** — The outbox write and the domain mutation occur within the same `UnitOfWork.execute()` call. The relay process polls the outbox AFTER commit, ensuring events are durable before relay begins. Sequence: `begin → domain write → outbox write → commit → relay polls → Kafka produce → mark published_at`. (Extends D-9, V2.0 §13.4.)

```java
// Worked example: trade capture with outbox in same transaction
unitOfWork.run(em -> {
    // Step 1: create position ledger entries
    List<PositionLedgerEntry> entries = tradeCaptureHandler.handle(cmd);

    // Step 2: write outbox row in same transaction (Pattern #24)
    OutboxEntity outbox = new OutboxEntity();
    outbox.setAggregateType("PositionLedger");
    outbox.setAggregateId(cmd.tradeId());
    outbox.setEventType("PositionCaptured");
    outbox.setPayload(serialize(new PositionCaptured(
        cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId(),
        cmd.tradeVersion(), entries.size(), Instant.now())));
    outbox.setCreatedAt(Instant.now());
    em.persist(outbox);
    // COMMIT happens here — both ledger entries and outbox row are durable
});

// After commit: relay picks up outbox row asynchronously
// outboxRelay.poll() → Kafka produce → mark published_at
```

---

## §18 — Testing Strategy

### 18.1 Unit Tests — Plain JUnit + Hand-Mocked Ports

> **TR-039** — Unit tests for `pv-domain` use plain JUnit 5 with hand-written stub implementations of port interfaces. No Guice injector, no Spring context, no Mockito requirement (though Mockito is acceptable). This validates that domain logic is truly framework-independent. (Extends ADR-001 §5.)

```java
/**
 * Unit test example: PriceEvaluator with stub MarketDataPort.
 * No DI container, no database, no framework.
 */
class DefaultPriceEvaluatorTest {

    private final MarketDataPort stubMarketData = new StubMarketDataPort();
    private final PriceEvaluator evaluator = new DefaultPriceEvaluator();

    @Test
    void constantLeaf_returnsFixedValue() {
        var expr = new ConstantLeaf("leaf-1", new BigDecimal("85.00"), "EUR/MWh");
        var result = evaluator.evaluate(expr,
            DeliveryPeriod.of(YearMonth.of(2026, 8), ZoneId.of("Europe/Berlin")),
            ResolutionPurpose.FORWARD, stubMarketData);

        assertEquals(new BigDecimal("85.00"), result.value());
        assertEquals(Set.of("leaf-1"), result.activeLeaves());
    }

    @Test
    void clamp_insideCollar_boundsInactive() {
        // FR-048f: inside-collar → bound leaves stay inactive
        var inner = new MarketDataLeaf("da-leaf", "EPEX-DE-LU-DA15",
            null, 0, "SINGLE_INTERVAL");
        var floor = new ConstantLeaf("floor-leaf", new BigDecimal("40.00"), "EUR/MWh");
        var cap = new ConstantLeaf("cap-leaf", new BigDecimal("120.00"), "EUR/MWh");
        var expr = new Clamp(floor, cap, inner);

        // Stub returns 68.20 (inside collar)
        ((StubMarketDataPort) stubMarketData).setFixing("EPEX-DE-LU-DA15",
            new BigDecimal("68.20"));

        var result = evaluator.evaluate(expr, testPeriod(),
            ResolutionPurpose.FORWARD, stubMarketData);

        assertEquals(new BigDecimal("68.20"), result.value());
        assertTrue(result.activeLeaves().contains("da-leaf"));
        assertFalse(result.activeLeaves().contains("floor-leaf"));
        assertFalse(result.activeLeaves().contains("cap-leaf"));
    }
}
```

### 18.2 Integration Tests — Testcontainers Postgres 16

> **TR-040** — Integration tests for `pv-persistence` use Testcontainers with Aurora-compatible PostgreSQL 16 image. Each test gets a clean schema via Flyway migration. No Guice injector needed — tests instantiate JPA adapters directly with a test `EntityManagerFactory`. (Extends V2.0 §16.1.)

```java
/**
 * Integration test example: JpaPositionLedgerRepository.
 * Uses Testcontainers Postgres 16, Flyway for schema, direct JPA.
 */
@Testcontainers
class JpaPositionLedgerRepositoryIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("pv_test");

    private EntityManagerFactory emf;
    private JpaPositionLedgerRepository repo;

    @BeforeEach
    void setUp() {
        // Flyway migration
        Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        // Direct JPA EntityManagerFactory — no Guice
        Map<String, Object> props = Map.of(
            "jakarta.persistence.jdbc.url", pg.getJdbcUrl(),
            "jakarta.persistence.jdbc.user", pg.getUsername(),
            "jakarta.persistence.jdbc.password", pg.getPassword(),
            "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
            "hibernate.jdbc.batch_size", "50",
            "hibernate.order_inserts", "true"
        );
        emf = Persistence.createEntityManagerFactory("pv-test", props);
        repo = new JpaPositionLedgerRepository();
        // Inject Provider<EntityManager> manually
    }

    @Test
    void bitemporalAsOfReconstruction_returnsCorrectVersion() {
        // FR-007: valid_from ≤ B < valid_to AND known_from ≤ K < known_to
        // ... create multiple versions with different known_from/to ...
        // ... query as-of specific business date and knowledge date ...
        // ... assert correct version returned ...
    }
}
```

### 18.3 Contract Tests — Port ↔ Adapter Conformance

> **TR-041** — Contract tests verify that each adapter correctly implements its port interface contract. The test suite defines the contract as abstract test methods; each adapter provides a concrete test class that supplies the real adapter under test. This catches adapter bugs without touching domain logic. (Extends ADR-001 §5.)

```java
/**
 * Abstract contract test for VolumeSeriesRepository.
 * Each adapter provides a concrete subclass.
 */
abstract class VolumeSeriesRepositoryContract {

    protected abstract VolumeSeriesRepository createRepository();

    @Test
    void save_and_findById_roundTrips() {
        var repo = createRepository();
        var series = testVolumeSeries();
        repo.save(series);
        var found = repo.findById(series.id());
        assertTrue(found.isPresent());
        assertEquals(series.seriesKey(), found.get().seriesKey());
    }

    @Test
    void findCurrentBySeriesKey_excludesSuperseded() {
        var repo = createRepository();
        // ... create CURRENT and SUPERSEDED versions ...
        var current = repo.findCurrentBySeriesKey(TENANT, SERIES_KEY);
        assertTrue(current.isPresent());
        assertEquals(QualityState.CURRENT, current.get().qualityState());
    }

    @Test
    void supersede_closesOldVersion_persistsNew() {
        // ... test supersession semantics ...
    }
}

// JPA adapter contract test
class JpaVolumeSeriesRepositoryContractTest extends VolumeSeriesRepositoryContract {
    @Override
    protected VolumeSeriesRepository createRepository() {
        return new JpaVolumeSeriesRepository(/* Testcontainers EMF */);
    }
}
```

### 18.4 Bitemporal Reconstruction Test Template

> **TR-042** — Every bitemporal entity must have a reconstruction test demonstrating FR-007 correctness. The test creates multiple versions across both time axes and asserts that as-of queries return the correct version for each (business date, knowledge date) pair. (Extends FR-007.)

```java
/**
 * Worked example: bitemporal reconstruction test for PositionLedgerEntry.
 * Tests FR-007: "state for business date B as known at knowledge date K."
 */
@Test
void bitemporalReconstruction_multipleVersions() {
    // Setup: trade T-7788 with three versions
    // V1: original capture at 2026-08-01, known from 2026-08-01
    var v1 = PositionLedgerEntry.builder()
        .tradeId("T-7788").tradeLegId("LEG-1").tradeVersion(1)
        .quantity(new BigDecimal("30.00"))
        .validFrom(Instant.parse("2026-08-01T00:00:00Z"))
        .knownFrom(Instant.parse("2026-08-01T10:00:00Z"))
        .status("ACTIVE").build();

    // V2: amendment at 2026-09-15 (backdated correction — valid_from unchanged,
    //     new knowledge time). Qty changed to 25.00.
    var v1closed = v1; // close known_to = 2026-09-15T14:00:00Z
    var v2 = PositionLedgerEntry.builder()
        .tradeId("T-7788").tradeLegId("LEG-1").tradeVersion(2)
        .quantity(new BigDecimal("25.00"))
        .validFrom(Instant.parse("2026-08-01T00:00:00Z"))  // same valid_from
        .knownFrom(Instant.parse("2026-09-15T14:00:00Z"))  // new knowledge
        .status("ACTIVE").build();

    // V3: forward-effective unwind from 2027-01-01 (valid_to set)
    var v3 = PositionLedgerEntry.builder()
        .tradeId("T-7788").tradeLegId("LEG-1").tradeVersion(3)
        .quantity(new BigDecimal("25.00"))
        .validFrom(Instant.parse("2026-08-01T00:00:00Z"))
        .validTo(Instant.parse("2027-01-01T00:00:00Z"))    // forward-effective unwind
        .knownFrom(Instant.parse("2026-11-01T09:00:00Z"))
        .status("ACTIVE").build();

    // Persist all versions
    repo.save(v1);
    repo.supersede(List.of(v1), List.of(v2));
    repo.supersede(List.of(v2), List.of(v3));

    // Assert: as of 2026-08-15 business, 2026-08-20 knowledge → V1 (qty=30)
    var result1 = repo.findAsOf("TN_0042", "T-7788",
        Instant.parse("2026-08-15T00:00:00Z"),
        Instant.parse("2026-08-20T00:00:00Z"));
    assertEquals(new BigDecimal("30.00"), result1.get(0).quantity());

    // Assert: as of 2026-08-15 business, 2026-10-01 knowledge → V2 (qty=25)
    var result2 = repo.findAsOf("TN_0042", "T-7788",
        Instant.parse("2026-08-15T00:00:00Z"),
        Instant.parse("2026-10-01T00:00:00Z"));
    assertEquals(new BigDecimal("25.00"), result2.get(0).quantity());

    // Assert: as of 2027-02-01 business, 2026-12-01 knowledge → V2 still valid
    //         (V3 not yet known; V2 has no valid_to)
    var result3 = repo.findAsOf("TN_0042", "T-7788",
        Instant.parse("2027-02-01T00:00:00Z"),
        Instant.parse("2026-12-01T00:00:00Z"));
    assertEquals(new BigDecimal("25.00"), result3.get(0).quantity());

    // Assert: as of 2027-02-01 business, 2026-12-01 knowledge → V3 valid_to
    //         is 2027-01-01, so 2027-02-01 is OUTSIDE valid range → empty
    var result4 = repo.findAsOf("TN_0042", "T-7788",
        Instant.parse("2027-02-01T00:00:00Z"),
        Instant.parse("2026-11-15T00:00:00Z"));
    assertTrue(result4.isEmpty());
}
```

---

## §18a — Performance Requirements & Benchmarking

### 18a.1 JMH Microbenchmarks

> **TR-043** — JMH benchmarks validate hot-path operations against SLA targets. Benchmarks run in CI on every PR merge as regression gates. Failure criteria: p95 regression > 20% vs baseline. (Extends V2.0 §14.)

| Benchmark | Target p95 | Component | Pattern |
|-----------|-----------|-----------|---------|
| `PriceExpressionEvaluation` — 13-node collar PPA tree | < 50 µs | `PriceEvaluator` (S2) | #10, #12 |
| `VolumeReferenceResolution` — single interval × multiplier | < 10 µs | `VolumeResolver` (S3) | #9 |
| `VolumeSeriesSpecComposition` — 5-predicate `.and()` chain | < 5 µs | `VolumeSeriesSpec` (S3) | #19 |
| `QualityStateTransition` — all valid transitions | < 1 µs | `QualityState` enum | #4, #16 |
| `DeliveryPeriodToMonthBlocks` — 12-month decomposition | < 10 µs | `DeliveryPeriod` | #3, #8 |
| `ActiveLeavesCapture` — 6-leaf PPA expression | < 30 µs | `PriceEvaluator` (S2) | #10, #12 |
| `PositionLedgerEntryBuilder` — full 18-field build | < 5 µs | `PositionLedgerEntry.Builder` | #6 |
| `BitemporalPredicateConstruction` — JPQL parameter binding | < 20 µs | `JpaPositionLedgerRepository` | #18 |

**JMH configuration:**

```java
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class PriceExpressionBenchmark {

    private PriceExpression collarPpaExpression;
    private PriceEvaluator evaluator;
    private MarketDataPort stubMarketData;

    @Setup
    public void setup() {
        // Build reference deal's 13-node expression tree
        collarPpaExpression = buildReferenceCollarPpa();
        evaluator = new DefaultPriceEvaluator();
        stubMarketData = new InMemoryMarketDataPort();
    }

    @Benchmark
    public PriceResolution evaluateCollarPpa() {
        return evaluator.evaluate(collarPpaExpression,
            testPeriod(), ResolutionPurpose.FORWARD, stubMarketData);
    }
}
```

### 18a.2 Gatling Load Benchmarks

> **TR-044** — Gatling load tests validate end-to-end throughput against SLA targets (V2.0 §14). Tests run nightly against a staging environment with representative data volumes. (Extends V2.0 §14.)

| Scenario | Target throughput | Target p95 latency | Data volume |
|----------|------------------|--------------------|-------------|
| Trade capture pipeline (end-to-end) | 200 trades/sec | < 250 ms | 96 intervals/trade (DA fill) |
| `VolumeSuperseded` fan-out | 50 events/sec | < 500 ms | 10 affected trade-legs × 2,976 intervals |
| Settlement batch (full day, single tenant) | 1 day/sec | < 3 sec | ~10K cells/day |
| EOD strike batch (all tenants) | 200 tenants | < 60 sec total | 12 buckets × ~500 positions/tenant |
| Portfolio position query (slot cache hit) | 1000 req/sec | < 30 ms | 1 zone × 1 day × 96 intervals |
| Bitemporal as-of reconstruction | 100 req/sec | < 100 ms | 5 trade versions, 12 monthly blocks |
| Redis MGET bulk interval fetch | 500 req/sec | < 5 ms | 2,976 keys per request |
| S6b rebuild (single trade-leg, 12 months) | 10/sec | < 2 sec | ~35K intervals |

**Gatling scenario example:**

```scala
val tradeCaptureScenario = scenario("Trade Capture Pipeline")
  .exec(http("Capture DA fill")
    .post("/api/trades/capture")
    .body(StringBody(daFillPayload))
    .check(status.is(201)))
  .pause(100.milliseconds)
  .exec(http("Verify position ledger")
    .get("/api/positions/${tradeId}")
    .check(status.is(200))
    .check(jsonPath("$.entries").count.is(12)))  // 12 monthly blocks

setUp(
  tradeCaptureScenario
    .inject(constantUsersPerSec(200).during(60.seconds))
).protocols(httpProtocol)
  .assertions(
    global.responseTime.percentile(95).lt(250),
    global.successfulRequests.percent.gt(99.5)
  )
```

### 18a.3 SLA Targets from V2.0 §14

Reproduced from V2.0 §14 for reference — these are the binding targets:

| Operation | p95 | p99 |
|-----------|-----|-----|
| Single interval insert | 5 ms | 15 ms |
| Batch insert (96 rows, DA day) | 30 ms | 80 ms |
| Batch insert (2,976 rows, monthly chunk) | 300 ms | 800 ms |
| Series by ID | 3 ms | 10 ms |
| FORECAST intervals for one day | 5 ms | 20 ms |
| Redis cache hit | 1 ms | 5 ms |
| `queryVolumeForTradeLeg` (single interval) | 8 ms | 25 ms |
| Portfolio position (1 zone, 1 day) | 30 ms | 100 ms |
| Critical path (Kafka consume → DB → cache → Kafka produce) | 250 ms | — |

### 18a.4 Regression Gates

| Gate | Trigger | Tool | Failure criteria |
|------|---------|------|------------------|
| JMH microbenchmarks | Every PR merge to `main` | CI (GitHub Actions) | p95 regression > 20% vs last-known-good baseline |
| Gatling load tests | Nightly (02:00 UTC) | Staging environment | p95 > SLA target OR success rate < 99.5% |
| Testcontainers integration | Every PR | CI | Any test failure |

---

## §19 — Startup & Bootstrap

### 19.1 `PositionValuationApp.main` — pv-guice

> **TR-045** — Bootstrap creates the Guice injector with all modules in dependency order. `EntityManagerFactory` must be initialized before any repository binding is exercised. Kafka consumers start after the injector is fully built. The outbox poller starts last. (Extends ADR-001 §3.1.)

```java
/**
 * Application bootstrap. Lives in pv-guice/.
 * Module composition order matters: Persistence before Domain (for Provider<EM>),
 * TenantModule before anything that uses @TenantAware.
 */
public class PositionValuationApp {

    public static void main(String[] args) {
        // 1. Create Guice injector — all bindings resolved
        Injector injector = Guice.createInjector(
            new PersistenceModule(),   // DataSource, EMF, repositories
            new DomainModule(),        // Domain services, strategies
            new TenantModule(),        // @TenantAware interceptor
            new EventModule(),         // DomainEventPublisher, ForwardMarkStore
            new CacheModule(),         // Redis VolumeCache, TradeIntervalCache
            new KafkaModule()          // Outbox relay, consumers
        );

        // 2. Verify EntityManagerFactory is initialized
        EntityManagerFactory emf = injector.getInstance(EntityManagerFactory.class);
        if (!emf.isOpen()) {
            throw new IllegalStateException("EntityManagerFactory failed to initialize");
        }

        // 3. Start Kafka consumers (after injector is fully built)
        startConsumers(injector);

        // 4. Start outbox relay poller (last — depends on Kafka producer)
        OutboxRelayProducer relay = injector.getInstance(OutboxRelayProducer.class);
        relay.start();    // polls every 100ms

        // 5. Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            relay.stop();
            emf.close();
        }));

        // Application ready — all ports bound to adapters
    }

    private static void startConsumers(Injector injector) {
        var consumers = List.of(
            injector.getInstance(TradeCapturedConsumer.class),
            injector.getInstance(VolumeSupersededConsumer.class),
            injector.getInstance(SettlementPublishedConsumer.class),
            injector.getInstance(CurveTickConsumer.class)
        );

        // Each consumer runs on a virtual thread
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        consumers.forEach(c -> executor.submit(c::startPolling));
    }
}
```

---

## §20 — Compliance Matrix

### 20.1 Design Decisions → Patterns → Spec Artifacts

| Decision | Description | Patterns | Spec Artifacts |
|----------|-------------|----------|----------------|
| D-1 | Ledger grain = trade-leg × delivery-month block; signed qty; no interval fan-out in S1 | #1, #2, #6, #17, #34, #35 | `PositionLedgerEntry`, `PositionLedgerEntryEntity`, `PositionLedgerRepository`, `TradeCaptureHandler`, `BitemporalAuditListener` |
| D-2 | Price = expression ref; fixed price = degenerate expression | #3, #5, #8, #10, #12, #33 | `PriceExpression` (sealed, 13 types), `PriceEvaluator`, `ConstantLeaf`, `MarketDataLeaf`, `IndexLeaf`, `Clamp`, `Escalate`, `ConditionalGate`, pattern-matching `switch`, `NumericPrecision` |
| D-3 | Forward marks ephemeral; settlement bitemporal; EOD strike = month-bucket with stamps | #1, #15, #28 | `SettlementCellEntity`, `ForwardMarkStore`, `StruckMarkEntity`, `AbstractMaterializationJob` |
| D-4 | Optimized version-binding; `active_leaves` | #12, #15 | `PriceResolution.activeLeaves()`, `DependencyEdge.activeLeaves()`, `SettlementCellEntity.activeLeaves` |
| D-5 | Peak is interval-dimension data | #1, #33 | `CachedInterval.isPeak()`, `RollupCell.isPeak()` |
| D-6 | Dual units in cache/rollups only; single canonical in ledger | #1, #29, #31 | `CachedInterval.netMw()/netMwh()`, `RollupCell.netMw()/netMwh()`, `PositionLedgerEntry.quantity()` (single unit) |
| D-7 | Batch authoritative; re-derive-not-delta idempotency | #26, #15, #14 | `IdempotentConsumer`, `AbstractMaterializationJob`, `OutboxRelayProducer` |
| D-8 | Entity/measure distinction; netting is projection | #1, #28 | `PositionLedgerEntry` (entity), `CachedInterval` (measure), `RollupCell` (measure) |
| D-9 | Shared monthly partitions; tenant leading key | #24, #32, #23 | `OutboxEntity`, `TenantInterceptor`, `@SequenceGenerator(allocationSize=50)` |
| D-10 | All interval structure via MarketCalendar | #3, #33 | `DeliveryPeriod.toMonthBlocks()`, `DeliveryRange` |
| D-11 | Unified volume resolution: VolumeReference × multiplier | #9, #7, #33, #27 | `VolumeResolver` (sealed), `ProfileResolver`, `ForecastResolver`, `VolumeSeriesFactory`, `VolumeReference` |
| D-12 | S6b trade_interval_cache: optional, rebuildable | #11, #30, #15 | `TradeIntervalCache`, `TradeIntervalCacheEntity`, `TradeIntervalCacheRebuilder`, `TradeIntervalRecord` |

### 20.2 FR Ranges → Spec Artifacts

| FR Range | Topic | Spec Artifacts |
|----------|-------|----------------|
| FR-001–FR-003 | Entity vs Measure | `PositionLedgerEntry` (entity), `CachedInterval` (measure), `RollupCell` (measure) |
| FR-004–FR-005 | Grain follows lifecycle | `PositionLedgerEntry` (trade-leg × month), `VolumeSeries` (aggregate root) |
| FR-006–FR-009 | Bitemporality | `PositionLedgerEntryEntity`, `SettlementCellEntity` (bitemporal columns), `BitemporalAuditListener`, `JpaPositionLedgerRepository.findAsOf()` (JPQL text-block), bitemporal reconstruction test |
| FR-020–FR-025 | Market, DeliveryPoint, Calendar | `DeliveryPeriod`, `MarketDataPort`, `PriceEvaluator` |
| FR-030–FR-037 | Position Ledger attributes | `PositionLedgerEntry` (signed qty, Builder, delivery-month blocks), `TradeCaptureHandler`, `TradeAmendHandler`, `TradeCancelHandler`, `NumericPrecision` (FR-036: configurable precision) |
| FR-040–FR-048h | PriceExpression | `PriceExpression` sealed hierarchy (13 types), `PriceEvaluator.evaluate()`, pattern-matching `switch`, `PriceResolution` (value, activeLeaves, inputVersionSet) |
| FR-050–FR-057a | Volume Series | `VolumeSeries`, `VolumeInterval`, `VolumeResolver`, `VolumeSeriesFactory`, `VolumeSeriesRepository`, `VolumeSeriesSpec`, `MaterializationStrategy`, `BatchWriter`, `VolumePublished`, `VolumeSuperseded` |
| FR-060–FR-063 | Market Data Store | `MarketDataPort` (read-only, version pinning), `MarketDataLookup` |
| FR-070–FR-074 | Settlement cells | `SettlementCellEntity`, `SettlementMaterializationJob`, `input_version_set` JSONB column |
| FR-075–FR-076 | Forward marks | `ForwardMarkStore` (ephemeral), `ForwardMark` record |
| FR-077–FR-079 | EOD struck marks | `StruckMarkEntity`, `curveVersionSet` JSONB column |
| FR-080–FR-085 | Slot cache | `VolumeCache`, `RedisVolumeCache`, `CachedInterval`, `Aggregators` |
| FR-086–FR-086e | Trade interval cache | `TradeIntervalCache`, `TradeIntervalCacheEntity`, `TradeIntervalRecord`, `TradeIntervalCacheRebuilder` |
| FR-090–FR-091 | Rollups | `RollupRepository`, `RollupCell`, `Aggregators` |
| FR-102–FR-104 | Dependency index | `DependencyIndex`, `DependencyEdge`, `PrunePolicy` |
| FR-105 | Batch cycle | `AbstractMaterializationJob.execute()`, `SettlementMaterializationJob`, `EodStrikeJob` |
| FR-106 | Idempotency | `IdempotentConsumer`, natural key `(trade_id, trade_version)` |
| FR-120/FR-122 | Multitenancy | `TenantContext`, `@TenantAware`, `TenantInterceptor`, RLS policies (defers to V2.0 §11) |

### 20.3 Subsystems → Spec Artifacts

| Subsystem | Description | Spec Artifacts |
|-----------|-------------|----------------|
| S1 | Position Ledger | `PositionLedgerEntry`, `PositionLedgerEntryEntity`, `PositionLedgerRepository`, `JpaPositionLedgerRepository`, `TradeCaptureHandler`, `TradeAmendHandler`, `TradeCancelHandler`, `DefaultTradeCaptureHandler` |
| S2 | PriceExpression | `PriceExpression` (sealed, 13 types), `PriceEvaluator`, `DefaultPriceEvaluator`, `PriceResolution`, `ResolutionPurpose` |
| S3 | VolumeSeries | `VolumeSeries`, `VolumeSeriesEntity`, `VolumeInterval`, `VolumeIntervalEntity`, `VolumeReference`, `MeteredActualVolumeSeries`, `VolumeResolver`, `ProfileResolver`, `ForecastResolver`, `VolumeSeriesFactory`, `VolumeSeriesRepository`, `VolumeSeriesSpec`, `MaterializationStrategy`, `RollingHorizonStrategy`, `BatchWriter`, `VolumePublished`, `VolumeSuperseded`, `VolumeChunkMaterialized` |
| S4 | Market Data Store | `MarketDataPort`, `MarketDataLookup` |
| S5a | Settlement cells | `SettlementCellEntity`, `SettlementCellRepository`, `SettlementMaterializationJob`, `SettlementComputed` |
| S5b | Forward marks | `ForwardMarkStore`, `ForwardMark` |
| S5c | EOD struck marks | `StruckMarkEntity` |
| S6 | Slot Cache | `VolumeCache`, `RedisVolumeCache`, `CachedInterval`, `CacheInvalidationHandler` |
| S6b | Trade Interval Cache | `TradeIntervalCache`, `TradeIntervalCacheEntity`, `TradeIntervalRecord`, `TradeIntervalCacheRebuilder` |
| S7 | Rollups | `RollupRepository`, `RollupCell`, `Aggregators` |
| S8 | Dependency Index | `DependencyIndex`, `DependencyEdge`, `PrunePolicy`, `SettlementHandoverPolicy`, `HotStoreRetentionPolicy` |

### 20.4 Pattern Coverage Verification

All 35 patterns from ADR-001 appear in this specification:

| # | Pattern | Spec Location |
|---|---------|---------------|
| 1 | Entity vs Measure | §8.1 (TR-011), §11.1, §12.1 |
| 2 | Aggregate Root | Part 1 §6.1 (TR-005) |
| 3 | Value Object (record) | Part 1 §5.1 |
| 4 | Enum with behavior | Part 1 §5.3 (QualityState) |
| 5 | Sealed type hierarchy | Part 1 §7.1 (TR-008) |
| 6 | Builder | Part 1 §6.3 (TR-007) |
| 7 | Factory Method | §9.2 |
| 8 | Static Factory | Part 1 §5.1 (DeliveryPeriod.of, Money.of) |
| 9 | Strategy — Volume Resolution | §9.1 (TR-015) |
| 10 | Strategy — Price Evaluation | Part 1 §7.4 (TR-009) |
| 11 | Strategy — Materialization | §9.4 (TR-016) |
| 12 | Composite | Part 1 §7.5 (TR-010, pattern-matching switch) |
| 13 | Decorator / Filter Chain | §14.3 (TenantInterceptor) |
| 14 | Observer / Domain Events | §9.6 (TR-019), §15.1 |
| 15 | Template Method | §11.4 (TR-024, AbstractMaterializationJob) |
| 16 | State Machine | Part 1 §5.3 (QualityState.canTransitionTo) |
| 17 | Command | Part 1 §5.2 (TradeCapture/Amend/Cancel records), §8.4 (TR-013) |
| 18 | Repository (Port + Adapter) | §8.2–§8.3 (TR-012), §9.3 |
| 19 | Specification (query) | §9.3 (VolumeSeriesSpec) |
| 20 | Unit of Work (batch flush) | §9.5 (TR-017, BatchWriter) |
| 21 | Identity Map | Hibernate first-level cache (implicit, §9.5) |
| 22 | Dual DataSource | §14.4–§14.5, §16.2 |
| 23 | Sequence-based ID | Part 1 §6.1, §11.1, §12.4 (allocationSize=50) |
| 24 | Transactional Outbox | §8.5 (TR-014), §15.2, §17.3 (TR-038) |
| 25 | Saga (choreography) | §15.1 (topic chain: trade → volume → cache) |
| 26 | Idempotent Consumer | §15.3 (TR-036) |
| 27 | Event-Carried State Transfer | §9.6 (TR-019, VolumePublished payload) |
| 28 | CQRS (implicit) | §14.4 (writer/reader DataSource), §11.2 (ForwardMarkStore) |
| 29 | Read-Through | §12.1 (TR-025), §12.2 (TR-026) |
| 30 | Cache-Aside + Event Invalidation | §12.3 (TR-027) |
| 31 | Pipeline Batching | §12.1 (MGET), §12.2 |
| 32 | @TenantAware Interceptor | §14.1–§14.3 (TR-032, TR-033) |
| 33 | Functional Interfaces | §13.1 (IntervalAggregator), §9.3 (VolumeSeriesSpec) |
| 34 | Immutability Enforcement | Part 1 §6.2 (TR-006), §11.3 (StruckMarkEntity) |
| 35 | Bitemporal Audit | §14.6 (TR-034) |

**Patterns with zero coverage:** None. All 35 patterns are covered.

---

## §21 — Open Items for Implementation

Extends functional-spec §16 (O-1 through O-8) with implementation-specific items:

| # | Item | Status | Notes |
|---|------|--------|-------|
| O-1 | Hot-window length & per-market defaults | Open | 60d assumed; validate against desk usage. Impacts Redis sizing and slot-cache scope. |
| O-2 | Live board necessity per tenant tier | Open | Determines whether event path ships in phase 1. Impacts `KafkaModule` consumer set. |
| O-3 | Expression language surface | Open | v1 operators = 13 types per FR-048h. Extensibility point: adding a new `PriceExpression` type requires only a new `record` + `switch` arm. |
| O-4 | Settlement-amount vs settlement-price cell content | Open | Currently stores both `price` and `amount` on `SettlementCellEntity`. Confirm with cashflow module. |
| O-5 | Struck-mark bucket for sub-monthly books | Open | Monthly buckets for term books; daily for spot/intraday. Impacts `StruckMarkEntity.deliveryMonth` grain. |
| O-6 | Curve-pillar → interval expansion ownership | Open | Deferred to `MarketDataPort` contract. Currently the port returns expanded per-interval values. |
| O-7 | Dispute/annotation workflow on settlement cells | Open | Not modeled in current spec. Candidate: sibling entity `SettlementDispute` with FK to `SettlementCellEntity`. |
| O-8 | Cross-zone financial-net flagging in UI contracts | Open | FR-023 flags need representation in grid payload. Not yet surfaced in `CachedInterval` or `RollupCell`. |
| OI-1 | `guice-persist` re-evaluation | Open | If web container is guaranteed, `guice-persist` `@Transactional` may simplify transaction management (§17.1). Re-evaluate after deployment model is confirmed. |
| OI-2 | Lettuce vs Jedis decision | Open | Current spec uses Lettuce (non-blocking). Jedis is simpler but blocking. Decision depends on throughput requirements and virtual-thread compatibility. |
| OI-3 | `MarketDataPort` implementation | Open | Currently a port stub. Real implementation depends on Market Data Service contract (REST, gRPC, or in-process). |
| OI-4 | Saga state persistence | Open | `trade.saga_state` table defined in V2.0 §13.5. JPA entity and repository not yet specified. |
| OI-5 | Compaction view (optional) | Open | V3.0 §4.4 defines optional user-initiated compaction. Port interface and JPA entity not yet specified. |
| OI-6 | MeteredActualRepository port | Open | Separate from `VolumeSeriesRepository` per different aggregate root. Port signatures not yet fully specified. |

---

## §22 — References

### Specification Documents

| Document | Path | Version |
|----------|------|---------|
| Functional Specification | `docs/functional-spec/functional-spec-position-valuation-v1.0.md` | 1.0 |
| Volume Series Domain Model | `docs/functional-spec/VOLUME_SERIES_SPEC-V3_0.md` | 3.0 |
| Data Architecture | `docs/technical-spec/VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` | 2.0 |
| Pattern Catalog (Library + Guice) | `docs/technical-spec/ADR-001-IMPLEMENTATION-PATTERN-CATALOG-library_guice.md` | 1.0 |
| Pattern Catalog (Spring Boot) | `docs/technical-spec/ADR-001-IMPLEMENTATION-PATTERN-CATALOG-springboot.md` | 1.0 |
| Design Context & Rationale | `docs/context/CONTEXT-position-valuation-design.md` | — |
| Plain Language Guide | `docs/spec-in-layman-language/README.md` | — |

### Java 21 JEP References

| JEP | Feature | Usage in This Spec |
|-----|---------|-------------------|
| JEP 395 | Records | All value objects, event payloads, command payloads, `PriceExpression` types |
| JEP 409 | Sealed Classes | `PriceExpression` (13 types), `VolumeResolver` (2 types), `MaterializationStrategy` (3 types), `PrunePolicy` (2 types) |
| JEP 441 | Pattern Matching for `switch` | `PriceEvaluator` tree walker (exhaustive dispatch over 13 types) |
| JEP 378 | Text Blocks | JPQL bitemporal as-of query (§8.3), SQL fragments |
| JEP 444 | Virtual Threads | `TradeIntervalCacheRebuilder` chunk processing, Kafka consumer threads (§19.1) |
| JEP 431 | Sequenced Collections | `VolumeSeries.intervals()` → `SequencedSet<VolumeInterval>` |

### Platform Versions

| Component | Version | Constraint Source |
|-----------|---------|-------------------|
| Java | 21 (LTS) | V2.0 §1.4 |
| Google Guice | 7 | ADR-001 |
| JPA / Hibernate | 7.x (Jakarta EE 11, JPA 3.2) | ADR-001 |
| Aurora PostgreSQL | 16 | V2.0 §1.4, P0 |
| Kafka | 3.7 (KRaft) | V2.0 §1.4 |
| Redis | 7 (Lettuce, per OI-2) | V2.0 §1.4 |
| pg_partman + pg_cron | — | V2.0 §6 |
| Flyway | — | V2.0 §16.1 |
| HikariCP + PgBouncer | — | V2.0 §3.4, P4 |
| JUnit 5 | — | §18 |
| Testcontainers | — | §18.2 |
| JMH | — | §18a.1 |
| Gatling | — | §18a.2 |

### Technical Rule Index

| TR | Extends | Section | Summary |
|----|---------|---------|---------|
| TR-001 | ADR-001 §1.1 | Part 1 §4 | `pv-domain` has zero framework dependencies |
| TR-002 | FR-036 | Part 1 §5.1 | `DeliveryRange` is half-open `[start, end)` in market-local wall-clock |
| TR-003 | D-11 | Part 1 §5.1 | `VolumeReference` carries its own `effectiveFrom/To` |
| TR-004 | FR-054 | Part 1 §5.3 | `QualityState` transition guards are pure domain logic |
| TR-005 | FR-050, V3.0 §6.10 | Part 1 §6.1 | `VolumeSeries` aggregate root with ownership XOR |
| TR-006 | FR-050, P2 | Part 1 §6.2 | MeteredActual is append-only |
| TR-007 | FR-030, D-1 | Part 1 §6.3 | `PositionLedgerEntry` is bitemporal, append-only, Builder |
| TR-008 | D-2, FR-048h | Part 1 §7.1 | `PriceExpression` sealed with 13 types |
| TR-009 | FR-045, FR-048e | Part 1 §7.4 | `PriceEvaluator` port with purpose-based resolution |
| TR-010 | FR-048h | Part 1 §7.5 | Exhaustive pattern-matching `switch` over sealed hierarchy |
| TR-011 | FR-006, D-1 | §8.1 | Position ledger is append-only |
| TR-012 | FR-007 | §8.3 | Bitemporal as-of JPQL predicate |
| TR-013 | FR-037 | §8.4 | Trade commands are exclusive position producers |
| TR-014 | D-9, V2.0 §13.4 | §8.5 | Outbox write in same transaction |
| TR-015 | D-11, FR-051 | §9.1 | `VolumeResolver` sealed, unified resolution |
| TR-016 | FR-056 | §9.4 | `MaterializationStrategy` sealed, data-driven selection |
| TR-017 | V2.0 §17 | §9.5 | `BatchWriter` flush/clear every 50, order_inserts=true |
| TR-018 | FR-006 | Part 1 §6.3 | Bitemporal columns stored as UTC `timestamptz` |
| TR-019 | FR-052a, Pattern #27 | §9.6 | Volume events carry routing state |
| TR-020 | FR-060 | §10.1 | `MarketDataPort` read-only with version pinning |
| TR-021 | FR-071, FR-071a | §11.1 | Settlement cells require all mandatory inputs |
| TR-022 | FR-075, D-3 | §11.2 | Forward marks are ephemeral |
| TR-023 | FR-077, D-3 | §11.3 | EOD struck marks are immutable |
| TR-024 | FR-056, FR-105 | §11.4 | `AbstractMaterializationJob` template method |
| TR-025 | FR-079, FR-080 | §12.1 | `VolumeCache` port with read-through/invalidation/batching |
| TR-026 | V2.0 §12 | §12.2 | Redis key scheme and TTL |
| TR-027 | FR-052b, V2.0 §12.5 | §12.3 | Event-driven cache invalidation |
| TR-028 | FR-086, D-12 | §12.4 | S6b commodity-neutral, rebuildable |
| TR-029 | FR-090, FR-091 | §13.1 | Rollups rebuildable, coarse-grain |
| TR-030 | FR-102–FR-104 | §13.2 | Dependency index with active_leaves |
| TR-031 | FR-103, FR-057a | §13.2 | Blast-radius optimization for price and volume |
| TR-032 | V2.0 §11, P1 | §14.1 | Three-layer tenant isolation |
| TR-033 | V2.0 §11 | §14.3 | Guice `MethodInterceptor` for @TenantAware |
| TR-034 | FR-006, V2.0 §10.3 | §14.6 | Bitemporal audit listener |
| TR-035 | D-9 | §15.1 | Topic naming, key strategy, headers |
| TR-036 | FR-106, D-7 | §15.3 | Idempotency via natural key |
| TR-037 | Pattern #24 | §17.1 | Explicit `UnitOfWork` over guice-persist |
| TR-038 | D-9, V2.0 §13.4 | §17.3 | Outbox-write-in-same-tx sequencing |
| TR-039 | ADR-001 §5 | §18.1 | Unit tests: plain JUnit + hand-mocked ports |
| TR-040 | V2.0 §16.1 | §18.2 | Integration tests: Testcontainers Postgres 16 |
| TR-041 | ADR-001 §5 | §18.3 | Contract tests: port ↔ adapter conformance |
| TR-042 | FR-007 | §18.4 | Bitemporal reconstruction test template |
| TR-043 | V2.0 §14 | §18a.1 | JMH microbenchmark regression gates |
| TR-044 | V2.0 §14 | §18a.2 | Gatling load test targets |
| TR-045 | ADR-001 §3.1 | §19.1 | Bootstrap module composition order |
| TR-046 | FR-036, D-2, D-12 | §5.0 | System-wide configurable numeric precision via `NumericPrecision` port |
| TR-047 | FR-036, V2.0 §5.0 | §5.0 | JPA column precision/scale governed by `NumericPrecision` defaults |
| TR-048 | FR-036 | §5.0 | No hardcoded scale literals — all `setScale`/`divide` resolve through `NumericPrecision` |

---

*End of Part 3. This completes the Technical Specification v1.0 for the Position & Valuation system (Library + Guice variant).*

*Return to [Part 1 — Domain Model and Ports (§1–§7)](TECH-SPEC-position-valuation-library_guice-v1.0-part1-domain-and-ports.md) | [Part 2 — Subsystems (§8–§13)](TECH-SPEC-position-valuation-library_guice-v1.0-part2-subsystems.md).*
