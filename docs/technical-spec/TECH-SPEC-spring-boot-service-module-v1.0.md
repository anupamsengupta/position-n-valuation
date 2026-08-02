# Technical Specification — Spring Boot Service Module (`pv-app`) v1.0

| Field | Value |
|---|---|
| **Status** | Implemented |
| **Date** | 2026-08-02 |
| **Module** | `pv-app` |
| **Companion Specs** | `TECH-SPEC-position-valuation-library_guice-v1.0` (parts 1–3), `integration-testing-tech-spec.md`, `ADR-001-IMPLEMENTATION-PATTERN-CATALOG-springboot.md` |
| **Companion Plan** | `docs/plan/spring-app-plan.md` (original implementation plan) |

---

## §1 — Purpose and Scope

The `pv-app` module is a runnable Spring Boot application that exposes the Position & Valuation domain model as REST endpoints. It provides:

- **Trade lifecycle API** — capture, amend, cancel via HTTP POST
- **Query API** — positions (current, as-of, by delivery range), settlements, volume series, market data
- **Async settlement pipeline** — outbox relay → Kafka → settlement materialization
- **Auto-seeded market data** — `stub/market-data.json` loaded into PostgreSQL on startup
- **Operational endpoints** — health check, cache statistics

The module bridges the existing `jakarta.inject`-based domain services with Spring Boot's DI container by providing a `ThreadLocal`-based `Provider<EntityManager>` implementation rather than adopting `spring-boot-starter-data-jpa`.

### 1.1 What This Module Is NOT

- Not a migration of the Guice DI framework — the `pv-guice` module remains the canonical production wiring
- Not an auto-configured JPA application — `DataSourceAutoConfiguration` and `HibernateJpaAutoConfiguration` are explicitly excluded
- Not a replacement for `pv-integration-tests` — that module tests against H2 with no framework; this module runs against PostgreSQL with full HTTP

---

## §2 — Architecture

```
  HTTP Request
       │
       ▼
  TenantFilter                     ← X-Tenant-Id header → ThreadLocalTenantContext
       │
       ▼
  REST Controller                  ← @RestController (Spring MVC)
       │
       ▼
  TransactionalExecutor            ← bind EM → begin TX → work → commit → unbind → close
       │
       ▼
  Domain Service                   ← DefaultTradeCaptureHandler, SettlementMaterializationJob, etc.
       │
  ┌────┴─────────────────┐
  │                      │
  ▼                      ▼
JPA Repository        OutboxDomainEventPublisher
(same EM)              (same EM, same TX)
  │                      │
  ▼                      ▼
PostgreSQL             trade.outbox table
                          │
                          ▼ (async, @Scheduled 500ms)
                    OutboxRelayScheduler
                          │
                          ▼
                    KafkaProducer → posval.PositionCaptured topic
                          │
                          ▼ (async, daemon polling thread)
                    TradeCapturedKafkaListener
                          │
                          ▼
                    TransactionalExecutor
                          │
                          ▼
                    TradeCapturedConsumer → SettlementMaterializationJob
                          │
                          ▼
                    valuation.settlement_cell (PostgreSQL)
```

### 2.1 Design Decision: Why Not `spring-boot-starter-data-jpa`

Every JPA adapter in `pv-persistence` (`JpaMarketDataRepository`, `JpaPositionLedgerRepository`, `BatchWriter`, `UnitOfWork`, `OutboxDomainEventPublisher`) accepts `jakarta.inject.Provider<EntityManager>` and manages the EM lifecycle themselves — calling `emProvider.get()`, using the EM for queries and persists, and relying on callers (like `UnitOfWork`) to handle begin/commit/close.

Spring Boot's `spring-boot-starter-data-jpa` brings its own `EntityManagerFactory` auto-configuration, `@PersistenceContext` injection, and `@Transactional` proxy-based transaction management. These would conflict with the existing `Provider<EntityManager>` pattern:

- Auto-configured EMF would compete with the manually configured one
- `@PersistenceContext` injects a shared proxy EM, not a fresh EM per `get()` call
- `@Transactional` proxies don't propagate the EM to components that call `emProvider.get()`

Instead, the module manually creates a `HikariDataSource` → `EntityManagerFactory` → `SpringEntityManagerProvider` (ThreadLocal-based) chain and provides a `TransactionalExecutor` that binds the EM for the duration of a request.

---

## §3 — Module Structure

```
pv-app/
  pom.xml                                          # Spring Boot BOM import (not parent)
  docker-compose.yml                               # PostgreSQL 16 + Kafka 3.7.1 (KRaft)
  src/main/
    resources/
      application.yml                              # DB, Kafka, server config
      META-INF/persistence.xml                     # PostgreSQL JPA unit (13 entities)
      db/init-schemas.sql                          # CREATE SCHEMA for 5 schemas
    java/com/power/posval/app/
      PvApplication.java                           # @SpringBootApplication entry point
      provider/
        SpringEntityManagerProvider.java           # ThreadLocal-based Provider<EntityManager>
        TransactionalExecutor.java                 # EM bind → begin → commit → unbind → close
      config/
        PersistenceConfig.java                     # DataSource, EMF, all JPA repos, outbox publisher
        DomainServiceConfig.java                   # Trade handlers, price evaluator, settlement job
        CacheConfig.java                           # In-memory cache beans
        MarketDataConfig.java                      # CachingMarketDataPort wiring
        TenantConfig.java                          # ThreadLocalTenantContext bean
        KafkaConfig.java                           # Producer, consumer, OutboxRelayProducer
      cache/
        InMemoryMarketDataCache.java               # ConcurrentHashMap-based MarketDataCache
        InMemoryVolumeCache.java                   # ConcurrentHashMap-based VolumeCache
      controller/
        TradeController.java                       # POST /api/trades/{capture,amend,cancel}
        PositionController.java                    # GET /api/positions, /as-of, /by-range
        SettlementController.java                  # GET /api/settlements
        MarketDataController.java                  # GET/POST fixings, forward-curves, indices, fx-rates
        VolumeSeriesController.java                # GET /api/volume-series
        HealthController.java                      # GET /api/health, /api/cache/stats
      dto/
        ApiResponse.java                           # { data, message, timestamp } wrapper
        TradeCaptureRequest.java                   # JSON → TradeCapture command
        TradeAmendRequest.java                     # JSON → TradeAmend command
        TradeCancelRequest.java                    # JSON → TradeCancel command
        PositionLedgerEntryDto.java                # PositionLedgerEntry → JSON
        SettlementCellDto.java                     # SettlementCell → JSON
        MarketDataRequest.java                     # JSON → MarketDataLookup
      seed/
        DataSeeder.java                            # ApplicationRunner: loads market-data.json
      kafka/
        OutboxRelayScheduler.java                  # @Scheduled outbox poller
        TradeCapturedKafkaListener.java            # Daemon Kafka consumer thread
      tenant/
        TenantFilter.java                          # Servlet filter: X-Tenant-Id → TenantContext
```

**File count**: 26 Java source files + 4 resource/config files + 1 Docker Compose file = **31 files**.

---

## §4 — The EntityManager Bridge

### 4.1 Problem Statement

All JPA adapters in `pv-persistence` call `emProvider.get()` to obtain an `EntityManager`. In the Guice runtime, Guice's `Provider<EntityManager>` creates a fresh EM per injection. In `pv-integration-tests`, a `ThreadLocal`-based lambda provider reuses the EM within a test scope.

For Spring Boot, a request-scoped EM is needed so that all components in a call chain — handler → repository → outbox publisher — share the same EM and participate in the same transaction.

### 4.2 `SpringEntityManagerProvider`

```java
public class SpringEntityManagerProvider implements Provider<EntityManager> {
    private final EntityManagerFactory emf;
    private final ThreadLocal<EntityManager> bound = new ThreadLocal<>();

    public void bind(EntityManager em)   // set thread-local
    public void unbind()                  // remove thread-local

    @Override
    public EntityManager get() {
        EntityManager em = bound.get();
        if (em != null && em.isOpen()) return em;
        return emf.createEntityManager();  // fallback: fresh EM
    }
}
```

**Semantics**: When bound (during `TransactionalExecutor.execute()`), all calls to `emProvider.get()` across the thread return the same EM. When unbound (outside a transaction), each call returns a fresh EM — matching the behavior of the Guice provider for non-transactional reads.

### 4.3 `TransactionalExecutor`

```java
public class TransactionalExecutor {
    public <T> T execute(Supplier<T> work) {
        EntityManager em = emf.createEntityManager();
        emProvider.bind(em);
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.get();
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            emProvider.unbind();
            if (em.isOpen()) em.close();
        }
    }
}
```

**Guarantees**:
- EM is always closed after use (no leak)
- EM is always unbound after use (no stale ThreadLocal)
- Transaction is always rolled back on exception
- All components in the call chain share the same EM/TX via `emProvider.get()`

### 4.4 Request Flow

```
POST /api/trades/capture
  → TenantFilter.doFilter()
      → tenantContext.setTenant("default")
  → TradeController.capture()
      → TransactionalExecutor.execute()
          → SpringEntityManagerProvider.bind(em)
          → em.getTransaction().begin()
          → TradeCaptureHandler.handle(cmd)
              → PositionLedgerRepository.save()        ← emProvider.get() returns bound EM
              → OutboxDomainEventPublisher.publish()    ← emProvider.get() returns same EM
          → em.getTransaction().commit()
          → SpringEntityManagerProvider.unbind()
          → em.close()
      → return 200 + ledger entries
  → TenantFilter (finally)
      → tenantContext.clear()
```

---

## §5 — Spring Configuration Classes

### 5.1 `PersistenceConfig` — The Core Bridge

Wires the entire persistence layer as Spring beans, reusing the exact same constructor-injection classes from `pv-persistence` that the Guice `PersistenceModule` binds:

| Bean | Type | Constructor Args |
|------|------|-----------------|
| `dataSource` | `HikariDataSource` | JDBC URL, user, password from `application.yml` |
| `entityManagerFactory` | `EntityManagerFactory` | `Persistence.createEntityManagerFactory("pv-unit", {nonJtaDataSource: ds})` |
| `springEntityManagerProvider` | `SpringEntityManagerProvider` | EMF |
| `entityManagerProvider` | `Provider<EntityManager>` | alias for `SpringEntityManagerProvider` |
| `transactionalExecutor` | `TransactionalExecutor` | EMF, `SpringEntityManagerProvider` |
| `batchWriter` | `BatchWriter` | `Provider<EntityManager>` (default batch size 50) |
| `unitOfWork` | `UnitOfWork` | `Provider<EntityManager>` |
| `positionLedgerRepository` | `JpaPositionLedgerRepository` | `Provider<EntityManager>`, `BatchWriter` |
| `jpaVolumeSeriesRepository` | `JpaVolumeSeriesRepository` | `Provider<EntityManager>`, `BatchWriter` |
| `settlementCellRepository` | `JpaSettlementCellRepository` | `Provider<EntityManager>`, `BatchWriter` |
| `jpaMarketDataRepository` | `JpaMarketDataRepository` | `Provider<EntityManager>` |
| `domainEventPublisher` | `OutboxDomainEventPublisher` | `Provider<EntityManager>` |

**Key difference from Guice `PersistenceModule`**: The Guice module uses `bind().to()` with `@Singleton` scope; the Spring config creates bean instances directly via `new`. The underlying classes are identical — they accept `jakarta.inject.Provider<EntityManager>` and work in both containers.

### 5.2 `DomainServiceConfig`

| Bean | Type | Constructor Args |
|------|------|-----------------|
| `numericPrecision` | `DefaultNumericPrecision` | (no args) |
| `tradeCaptureHandler` | `DefaultTradeCaptureHandler` | `PositionLedgerRepository`, `DomainEventPublisher` |
| `tradeAmendHandler` | `DefaultTradeAmendHandler` | `PositionLedgerRepository`, `DomainEventPublisher` |
| `tradeCancelHandler` | `DefaultTradeCancelHandler` | `PositionLedgerRepository`, `DomainEventPublisher` |
| `priceEvaluator` | `DefaultPriceEvaluator` | `NumericPrecision` |
| `priceExpressionRepository` | `JsonPriceExpressionRepository` | (no args, loads from classpath) |
| `volumeSeriesRepository` | tenant-normalizing wrapper | delegates to `JpaVolumeSeriesRepository` |
| `volumeResolver` | `ProfileResolver` | `VolumeSeriesRepository`, `NumericPrecision` |
| `settlementMaterializationJob` | `SettlementMaterializationJob` | 7 dependencies |

### 5.3 Tenant-Normalizing `VolumeSeriesRepository` Wrapper

`JpaVolumeSeriesRepository.toEntity()` hardcodes `tenantId = "default"` in its entity mapping. `ProfileResolver` passes `ref.tradeId()` as the `tenantId` parameter to `findCurrentBySeriesKey()`. The wrapper intercepts these calls and normalizes the tenant parameter to `"default"`, matching the pattern established in `IntegrationTestWiring`:

```java
@Bean
public VolumeSeriesRepository volumeSeriesRepository(JpaVolumeSeriesRepository jpaRepo) {
    return new VolumeSeriesRepository() {
        @Override
        public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String sk) {
            return jpaRepo.findCurrentBySeriesKey("default", sk);
        }
        // ... all methods delegate with tenant normalization
    };
}
```

### 5.4 `KafkaConfig`

| Bean | Type | Config |
|------|------|--------|
| `kafkaProducer` | `KafkaProducer<String, String>` | bootstrap from `application.yml`, idempotent, acks=all |
| `kafkaConsumer` | `KafkaConsumer<String, String>` | group `pv-settlement-consumer`, earliest offset |
| `outboxRelayProducer` | `OutboxRelayProducer` | `Provider<EntityManager>`, `KafkaProducer` |
| `tradeCapturedConsumer` | `TradeCapturedConsumer` | repos + settlement job |

---

## §6 — REST API Surface

### 6.1 Trade Lifecycle Endpoints

| Method | Path | Request Body | Handler | Response |
|--------|------|-------------|---------|----------|
| `POST` | `/api/trades/capture` | `TradeCaptureRequest` | `TradeCaptureHandler.handle()` | `ApiResponse<List<PositionLedgerEntryDto>>` |
| `POST` | `/api/trades/amend` | `TradeAmendRequest` | `TradeAmendHandler.handle()` | `ApiResponse<List<PositionLedgerEntryDto>>` |
| `POST` | `/api/trades/cancel` | `TradeCancelRequest` | `TradeCancelHandler.handle()` | `ApiResponse<List<PositionLedgerEntryDto>>` |

All trade endpoints wrap work in `TransactionalExecutor.execute()`, ensuring the trade handler, position ledger save, and outbox event publish participate in a single RESOURCE_LOCAL transaction.

### 6.2 Query Endpoints

| Method | Path | Query Params | Repository Method |
|--------|------|-------------|-------------------|
| `GET` | `/api/positions` | `tenantId, tradeId, tradeLegId` | `findCurrentByTradeLeg()` |
| `GET` | `/api/positions/as-of` | `tenantId, tradeId, tradeLegId, businessDate, knowledgeDate` | `findAsOf()` |
| `GET` | `/api/positions/by-range` | `tenantId, deliveryStart, deliveryEnd` | `findAllByDeliveryRange()` |
| `GET` | `/api/settlements` | `tenantId, positionId, rangeStart, rangeEnd` | `findByPosition()` |
| `GET` | `/api/volume-series` | `tenantId` | `findByTenantId()` |
| `GET` | `/api/volume-series/{id}` | — | `findById()` |

### 6.3 Market Data Endpoints

| Method | Path | Params / Body | Repository Method |
|--------|------|--------------|-------------------|
| `GET` | `/api/market-data/fixings` | `tenantId, series, intervalStart` | `findFixing()` |
| `POST` | `/api/market-data/fixings` | JSON body | `saveFixing()` |
| `GET` | `/api/market-data/forward-curves` | `tenantId, series, pillar, asOfDate` | `findForwardCurve()` |
| `POST` | `/api/market-data/forward-curves` | JSON body | `saveForwardCurve()` |
| `GET` | `/api/market-data/indices` | `tenantId, series, refMonth` | `findIndex()` |
| `POST` | `/api/market-data/indices` | JSON body | `saveIndex()` |
| `GET` | `/api/market-data/fx-rates` | `tenantId, pair, referenceDate` | `findFxRate()` |
| `POST` | `/api/market-data/fx-rates` | JSON body | `saveFxRate()` |

### 6.4 Operational Endpoints

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/api/health` | `{ status: UP/DOWN, database: connected/unreachable, emfOpen: true }` |
| `GET` | `/api/cache/stats` | `{ marketData: { size, hits, misses }, volume: { size } }` |

---

## §7 — DTO Layer

### 7.1 Request DTOs

Request DTOs use Jackson-friendly types (String dates, String UUIDs) with `toCommand()` methods that construct domain command records:

| DTO | Domain Command | Key Conversion Logic |
|-----|----------------|---------------------|
| `TradeCaptureRequest` | `TradeCapture` (16 fields) | Parses `deliveryStart/End` → `DeliveryPeriod.of()`, `quantity` → `BigDecimal`, `volumeUnit` → `VolumeUnit.valueOf()`, `priceExpressionId` → `UUID.fromString()`, `volumeSeriesKey` → `SeriesKey` |
| `TradeAmendRequest` | `TradeAmend` (11 fields) | Nullable fields remain null (unchanged). `amendmentReason` is a String (`BACKDATED_CORRECTION` or `FORWARD_EFFECTIVE`) |
| `TradeCancelRequest` | `TradeCancel` (6 fields) | `cancellationType` is a String (`FORWARD_UNWIND` or `VOID_AB_INITIO`) |
| `MarketDataRequest` | `MarketDataLookup` | Multipurpose DTO for all four market data types; uses applicable fields per type |

### 7.2 Response DTOs

| DTO | Domain Model | Flattening |
|-----|-------------|-----------|
| `PositionLedgerEntryDto` | `PositionLedgerEntry` | `id` → String, `deliveryRange.startMonth()/endMonth()` → String, `priceExpressionId` → String, bitemporal axes preserved |
| `SettlementCellDto` | `SettlementCell` | `cellId/positionId` → String, all fields preserved including `activeLeaves` set |

### 7.3 `ApiResponse<T>` Envelope

All responses wrapped in a consistent envelope:

```json
{
  "data": <T>,
  "message": "OK",
  "timestamp": "2026-08-02T14:30:00Z"
}
```

---

## §8 — Async Settlement Pipeline

### 8.1 Event Flow

The complete async pipeline from trade capture to queryable settlement cells:

```
Phase 1: Trade Capture (synchronous, within HTTP request)
─────────────────────────────────────────────────────────
  POST /api/trades/capture
    → TransactionalExecutor.execute()
      → TradeCaptureHandler.handle(cmd)
        → ledgerRepo.save() × N months     → position.position_ledger_entry
        → eventPublisher.publish()          → trade.outbox (published_at = NULL)
      → TX commit

Phase 2: Outbox Relay (async, @Scheduled every 500ms)
─────────────────────────────────────────────────────
  OutboxRelayScheduler.relay()
    → TransactionalExecutor.execute()
      → OutboxRelayProducer.relay()
        → SELECT FROM trade.outbox WHERE published_at IS NULL (max 100)
        → KafkaProducer.send("posval.PositionCaptured", payload)
        → UPDATE trade.outbox SET published_at = NOW()
      → TX commit

Phase 3: Settlement Materialization (async, Kafka consumer)
───────────────────────────────────────────────────────────
  TradeCapturedKafkaListener (daemon thread, poll loop)
    → consumer.poll(1s) → posval.PositionCaptured topic
    → deserialize → PositionCaptured event
    → tenantContext.setTenant(event.tenantId())
    → TransactionalExecutor.execute()
      → TradeCapturedConsumer.handle(event)
        → IdempotentConsumer.alreadyProcessed() check
        → ledgerRepo.findCurrentByTradeLeg()
        → for each PositionLedgerEntry:
            → SettlementMaterializationJob.execute(entry, deliveryRange)
              → ProfileResolver.resolve()        → volume intervals
              → PriceEvaluator.evaluate(EXPR-4)  → price with collar + CPI
              → cellRepo.saveAll()               → valuation.settlement_cell
              → eventPublisher.publish(SettlementComputed)
      → TX commit
    → tenantContext.clear()

Phase 4: Query (synchronous)
────────────────────────────
  GET /api/settlements?tenantId=default&positionId=<uuid>&rangeStart=...&rangeEnd=...
    → TransactionalExecutor.execute()
      → cellRepo.findByPosition()
    → return SettlementCellDto list
```

### 8.2 `OutboxRelayScheduler`

- Spring `@Scheduled(fixedRate)` with rate configurable via `pv.outbox.relay-interval-ms` (default 500ms)
- Wraps `OutboxRelayProducer.relay()` in `TransactionalExecutor`
- Logs relay count when > 0
- Catches exceptions to prevent scheduler thread death

### 8.3 `TradeCapturedKafkaListener`

- Implements `SmartLifecycle` for clean startup/shutdown
- Runs a daemon thread that polls `posval.PositionCaptured` topic
- Deserializes JSON payload to `PositionCaptured` event record
- Sets tenant context before processing, clears in finally block
- Wraps each message processing in `TransactionalExecutor`

### 8.4 Idempotency

`TradeCapturedConsumer` extends `IdempotentConsumer<PositionCaptured>`. The `alreadyProcessed()` check queries `VolumeSeriesRepository.existsByTradeIdAndTradeVersion()` — if a volume series already exists for this `(tradeId, tradeVersion)`, the event is skipped. This makes the consumer safe for at-least-once delivery.

---

## §9 — Data Seeding

### 9.1 `DataSeeder`

`DataSeeder implements ApplicationRunner` — runs after Spring Boot startup:

1. **Guard check**: queries for a known fixing (`EPEX_DA15` at `2025-03-01T00:00:00Z`). If present, skips seeding.
2. **Load**: reads `stub/market-data.json` from classpath (bundled with `pv-domain`)
3. **Parse**: uses the same hand-rolled JSON extraction as `MarketDataDbLoader` (regex-based `extractObject`, `extractKeys`, `extractKeyValuePairs`)
4. **Persist**: saves all entries via `JpaMarketDataRepository` within a single `TransactionalExecutor` transaction
5. **Log**: "Seeded 8832 fixings, 120 forward curves, 10 indices, 20 FX rates"

Seeding can be disabled via `pv.seed.enabled=false` in `application.yml`.

### 9.2 Price Expressions

Price expressions (e.g., EXPR-4 CPI-escalated collar) remain in-memory via `JsonPriceExpressionRepository`, which loads from `stub/price-expressions.json` at construction time. This matches the integration test approach — price expression trees are static contract terms, not market data.

---

## §10 — Tenant Handling

### 10.1 `TenantFilter`

A `@Component` servlet filter with `@Order(1)` that runs before any controller:

1. Reads `X-Tenant-Id` header from HTTP request
2. Falls back to `"default"` if header is absent or blank
3. Sets `ThreadLocalTenantContext.setTenant(tenantId)`
4. Calls `chain.doFilter()` to continue request processing
5. Clears tenant context in `finally` block

### 10.2 Tenant Context Propagation

```
HTTP request (X-Tenant-Id: acme)
  → TenantFilter → tenantContext.setTenant("acme")
    → Controller → TransactionalExecutor
      → Domain Service → CachingMarketDataPort.lookupFixing()
        → tenantContext.currentTenantId() → "acme"
        → cache key: "acme|FIXING|EPEX_DA15|2025-03-01T00:00:00Z"
        → DB query: WHERE tenant_id = 'acme'
```

---

## §11 — Infrastructure

### 11.1 Docker Compose

Two services:

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| `postgres` | `postgres:16-alpine` | 5432 | PostgreSQL with schemas auto-created via `init-schemas.sql` |
| `kafka` | `apache/kafka:3.7.1` | 9092 | KRaft single-node (no ZooKeeper), auto-creates topics |

PostgreSQL is initialized with 5 schemas via Docker's entrypoint mechanism:
- `market_data` — fixings, forward curves, FX rates, indices, spreads, vol surfaces
- `volume_series` — series + intervals
- `position` — position ledger entries
- `valuation` — settlement cells, struck marks, trade interval cache
- `trade` — outbox

### 11.2 `persistence.xml`

Persistence unit `pv-unit` with `RESOURCE_LOCAL` transactions. Same 13 entity classes as `pv-integration-tests`. Key differences from the integration test config:

| Property | Integration Tests (H2) | `pv-app` (PostgreSQL) |
|----------|----------------------|---------------------|
| `hbm2ddl.auto` | `create-drop` | `update` |
| `show_sql` | `true` | `false` |
| JDBC config | embedded in persistence.xml | injected via `PersistenceConfig` at runtime |
| `globally_quoted_identifiers` | `true` | `true` |

No JDBC URL, username, or password in `persistence.xml` — these are injected programmatically via `Persistence.createEntityManagerFactory("pv-unit", props)` with the `jakarta.persistence.nonJtaDataSource` property set to the HikariCP `DataSource`.

### 11.3 `application.yml`

```yaml
server:
  port: 8080

pv:
  datasource:
    url: jdbc:postgresql://localhost:5432/posval
    username: posval
    password: posval
    pool-size: 10
  kafka:
    bootstrap-servers: localhost:9092
  outbox:
    relay-interval-ms: 500
  seed:
    enabled: true
```

All configuration under the `pv` prefix — no Spring auto-config properties used.

---

## §12 — Maven Configuration

### 12.1 Spring Boot BOM Import (Not Parent)

The root POM uses its own `<groupId>com.power</groupId>` parent structure. To avoid conflict, `pv-app` imports the Spring Boot BOM via `<dependencyManagement>` rather than using `spring-boot-starter-parent`:

```xml
<dependencyManagement>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>3.3.5</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencyManagement>
```

### 12.2 Dependency Summary

| Dependency | Purpose | Source |
|-----------|---------|--------|
| `spring-boot-starter-web` | Embedded Tomcat, Jackson, Spring MVC | Spring Boot BOM |
| `spring-boot-starter-actuator` | Health endpoint | Spring Boot BOM |
| `pv-domain` | Domain model, ports, services | Internal |
| `pv-persistence` | JPA entities, adapters, batch writer | Internal |
| `pv-kafka` | OutboxRelayProducer, TradeCapturedConsumer | Internal |
| `jakarta.inject-api` 2.0.1 | `Provider<EntityManager>` interface | Root POM managed |
| `jakarta.persistence-api` 3.2.0 | JPA API | Root POM managed |
| `hibernate-core` 7.0.0.Final | JPA implementation | Root POM managed |
| `postgresql` 42.7.4 | JDBC driver | Root POM managed |
| `HikariCP` 6.2.1 | Connection pool | Root POM managed |
| `kafka-clients` 3.7.1 | Kafka producer/consumer | Root POM managed |
| `jackson-databind` + `jackson-datatype-jsr310` | JSON serialization | Spring Boot BOM |

### 12.3 Excluded Auto-Configurations

```java
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
```

These would conflict with the manual `PersistenceConfig` bean definitions.

---

## §13 — Caching

### 13.1 `InMemoryMarketDataCache`

`ConcurrentHashMap`-based implementation of `MarketDataCache` (domain port). Key format: `"{tenantId}|{type}|{series}|{lookupKey}"`.

Tracks hit/miss counters via `AtomicInteger` — exposed through `/api/cache/stats`.

### 13.2 `InMemoryVolumeCache`

`ConcurrentHashMap`-based implementation of `VolumeCache` (domain port). Key format: `"{tenantId}|{seriesKey}|{intervalStart}"`.

Both caches use prefix-based invalidation — `removeIf(k -> k.startsWith(prefix))`.

### 13.3 Production Cache Considerations

These in-memory caches are suitable for single-instance development. For multi-instance production deployment, the `pv-redis` module provides Redis-backed implementations with the same port interfaces. Swapping is a configuration change — replace `CacheConfig` beans with Redis adapter beans.

---

## §14 — Startup and Verification

### 14.1 Startup Sequence

```
docker compose up -d                    # Start PostgreSQL + Kafka
mvn clean install -pl pv-app -am        # Build with dependencies
mvn -pl pv-app spring-boot:run          # Start on port 8080
```

Expected startup log:
```
... Started PvApplication in X.XX seconds
... Seeding market data from stub/market-data.json...
... Seeded 8832 fixings, 120 forward curves, 10 indices, 20 FX rates
... Started Kafka listener for topic: posval.PositionCaptured
```

### 14.2 Smoke Tests

| Step | Command | Expected |
|------|---------|----------|
| 1 | `GET /api/health` | `{ "status": "UP", "database": "connected" }` |
| 2 | `GET /api/market-data/fixings?tenantId=default&series=EPEX_DA15&intervalStart=2025-03-01T00:00:00Z` | Returns seeded fixing value |
| 3 | `POST /api/trades/capture` (with trade payload) | Returns 12 ledger entries (1 per delivery month) |
| 4 | Wait ~2 seconds (outbox relay + Kafka consumer) | — |
| 5 | `GET /api/settlements?tenantId=default&positionId=<uuid>&rangeStart=2025-03-01T00:00:00Z&rangeEnd=2026-03-01T00:00:00Z` | Returns settlement cells with CPI-escalated prices |
| 6 | `GET /api/cache/stats` | Shows cache hits > 0 |

---

## §15 — Relationship to Other Modules

| Module | Relationship to `pv-app` |
|--------|--------------------------|
| `pv-domain` | Provides all domain model records, port interfaces, and service implementations. `pv-app` depends on but does not modify these. |
| `pv-persistence` | Provides JPA entity classes and adapter implementations. `pv-app` instantiates these via `new` with `Provider<EntityManager>` from `SpringEntityManagerProvider`. |
| `pv-kafka` | Provides `OutboxRelayProducer` and `TradeCapturedConsumer`. `pv-app` instantiates these and drives them via `OutboxRelayScheduler` and `TradeCapturedKafkaListener`. |
| `pv-guice` | Production Guice wiring. `pv-app` does NOT depend on `pv-guice`. Both modules wire the same underlying classes, using different DI containers. |
| `pv-redis` | Production Redis cache. `pv-app` uses in-memory caches instead. Can be swapped in by changing `CacheConfig`. |
| `pv-integration-tests` | H2-based testing with manual DI. Shares the same wiring pattern as `pv-app` but targets H2, not PostgreSQL, and has no HTTP layer. |

---

## §16 — Known Limitations

1. **No RLS enforcement** — `TenantFilter` sets `ThreadLocalTenantContext` but does not execute `SET LOCAL app.tenant_id` on the PostgreSQL connection. RLS policies are not enforced in this module. For production, the `TenantInterceptor` from `pv-guice` sets the session variable.

2. **In-memory caches** — `InMemoryMarketDataCache` and `InMemoryVolumeCache` are single-JVM only. Not suitable for multi-instance deployment without switching to Redis.

3. **No Flyway migrations** — schema management uses `hibernate.hbm2ddl.auto=update` for convenience. Production deployments should use Flyway DDL scripts.

4. **Tenant normalization wrapper** — the `VolumeSeriesRepository` bean in `DomainServiceConfig` normalizes all tenant parameters to `"default"`. This is adequate for single-tenant development but must be removed for multitenant production use.

5. **Kafka consumer uses `KafkaConsumer` directly** — not Spring Kafka's `@KafkaListener`. This matches the existing `pv-kafka` module's approach but means no Spring-managed consumer group rebalancing or error handling.

6. **No authentication/authorization** — all endpoints are unauthenticated. For production, add Spring Security or a gateway.
