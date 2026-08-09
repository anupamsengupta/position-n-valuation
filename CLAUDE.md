# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Multitenant CTRM (Commodity Trading and Risk Management) for EU power markets (~200 tenants). Covers position generation, price expression evaluation, settlement valuation, and volume series management. Java 21 / Spring Boot 3.3.5 / Guice 7 / Hibernate 7 / PostgreSQL 16 / Kafka 3.7 KRaft / Redis 7.

## Library-First Design (READ THIS FIRST)

This is the single most important thing to understand about this codebase. Getting it wrong causes cascading regressions.

**`pv-domain` + `pv-persistence` + `pv-kafka` + `pv-redis` + `pv-guice` together form a reusable, framework-agnostic library.** Guice 7 is the **canonical and primary DI**. The library must remain launchable from any host — a plain `main`, Quarkus, AWS Lambda, or another framework — without touching any module below `pv-app`.

**`pv-app` is a Spring Boot simulator / dev-harness for the library, NOT the production host.** See the next section — do not conflate the two.

Wiring rules:
- New domain services and port bindings go in `pv-guice` (`DomainModule.java` or a focused submodule such as `HandlersModule`, `QueryServicesModule`).
- Spring `@Bean` methods in `pv-app` config classes must delegate to `injector.getInstance(...)`. They do NOT construct domain classes with `new`. The simulator boots a Guice `Injector` at startup and exposes Guice-created instances as Spring beans so `@RestController` and Spring Kafka listeners can consume them.
- Config that must cross the Spring→Guice boundary (e.g. `pv.pricing.strategy`) is read from Spring's `Environment` in a `pv-app` module and passed into a Guice `ConfigModule` at `Injector` creation time.
- Any change that makes the library un-launchable from a non-Spring host regresses the design.

## `pv-app` is a Simulator, Not the Production Host

`pv-app` exists to exercise the library end-to-end from a Spring host and to provide REST demos and smoke tests. It is deliberately **single-tenant** (hardcoded `"default"`), uses **in-memory `MarketDataCache` / `VolumeCache` adapters**, runs `hbm2ddl.auto=update` against a **Hibernate-generated schema** (not Flyway), and does NOT enforce PostgreSQL Row-Level Security. These are intentional simulator-scope choices, not gaps to be fixed inside `pv-app`.

**The production hosting layer is a separate, planned deliverable.** It does not exist in this repo yet. When it lands, it will bring:
- Real per-request tenant propagation (`X-Tenant-Id` header → `TenantContext` → `SET LOCAL app.tenant_id` bound to the transaction's connection)
- Row-Level Security policies enforced in Postgres
- Flyway-managed schema (V2.0 §5 DDL, partitions via `pg_partman`, `pg_cron` maintenance, triggers, RLS policies)
- Real cache adapters (`RedisMarketDataCache`, `RedisVolumeCache`)
- Real `DataSourceRouter` query-time routing between writer and reader Aurora endpoints
- Real `@TenantAware` interceptor with a working `@TenantId` parameter annotation
- Authentication / authorization

Simulator-scope shortcuts in `pv-app` must not leak into library modules (see D-14 below). Library modules must be written as if production is watching — because it is, just from a host that doesn't exist yet.

## Module Architecture

```
pv-domain              Pure domain — @jakarta.inject ONLY, zero framework deps
├── pv-persistence     JPA/Hibernate adapters, BatchWriter, OutboxDomainEventPublisher
├── pv-redis           Redis cache adapters (Lettuce) — volume, market data, forward marks
├── pv-kafka           Kafka consumers + OutboxRelayProducer
└── pv-guice           Guice 7 module wiring — CANONICAL wiring for the library
     └── pv-app        Spring Boot SIMULATOR / DEV-HARNESS
          │            (single-tenant, in-memory caches, hbm2ddl)
          │            NOT a production host — boots the Guice Injector
          │            and exposes it via Spring for REST demos
          └── pv-integration-tests   End-to-end tests

(Future) production hosting module — TBD. Not in this repo yet.
```

**Hexagonal architecture:** Domain defines port interfaces (`pv-domain/port/`), infrastructure modules implement them. Controllers depend on service interfaces in `pv-domain/port/service/`, never on repositories directly.

## Build Commands

```bash
# Full build
mvn clean install

# Compile only (fast check)
mvn compile

# Run all tests
mvn test

# Run tests for a single module
mvn test -pl pv-domain
mvn test -pl pv-guice
mvn test -pl pv-kafka
mvn test -pl pv-app

# Run a single test class
mvn test -pl pv-domain -Dtest=SettlementMaterializationJobTest

# Run integration tests (Testcontainers PostgreSQL 16 — production-realistic)
mvn test -pl pv-integration-tests

# Skip tests
mvn -DskipTests clean install

# Verify library-first invariant: no Spring imports outside pv-app
grep -rn "org.springframework" pv-domain pv-persistence pv-kafka pv-redis pv-guice
# Must return zero results.

# Verify simulator-scope patterns don't leak into the library
grep -rn '"default"' pv-domain pv-persistence pv-kafka pv-redis pv-guice | grep -i tenant
# Hardcoded default tenants must live only in pv-app.

# Start local infrastructure (PostgreSQL, Redis, Kafka, UIs) — for pv-app simulator
cd pv-app && docker-compose up -d
```

**Local services:** PostgreSQL on `:9432` (posval/posval), Redis on `:6379`, Kafka on `:9092`, Conduktor UI on `:8081`, RedisInsight on `:5540`.

## Domain Subsystems (S1–S8)

| Sub | Name | Key Classes |
|-----|------|-------------|
| S1 | Position Ledger | `PositionLedgerEntry` — bitemporal, grain = trade-leg × delivery-month (D-1) |
| S2 | PriceExpression | Versioned expression tree; fixed price = degenerate expression (D-2) |
| S3 | VolumeSeries | `VolumeReference × multiplier` — FORECAST per asset, PROFILE per trade (D-11) |
| S4 | Market Data | Fixings, forward curves, FX rates, indices, vol surfaces, spreads |
| S5a | Settlement Cells | 15-min interval measures with price, amount, marketPrice, pnl |
| S5b | Forward Marks | Ephemeral current-state only (D-3) |
| S7 | Rollups | Materialized aggregates from settlement cells (TWA for MW, sum for MWh) |
| S8 | Dependency Index | `dependency_edge` table — blast-radius optimization for revaluation (FR-103) |

## Key Event Flows

**Trade capture:** `POST /api/trades/capture` → `DefaultTradeCaptureHandler` → outbox → Kafka `posval.PositionEntryCaptured` → `TradeCapturedConsumer` → `SettlementMaterializationJob` → settlement cells → `SettlementComputed` → rollup materialization.

**Market data revaluation:** `MarketDataUpdated` → `MarketDataUpdatedConsumer` (uses S8 `DependencyIndex` for targeted position lookup, not brute-force scan) → `SettlementRevaluationRequested` → `SettlementRevaluationService.revalue()` (sub-month interval precision).

**Volume revaluation:** `VolumeSuperseded` → `VolumeSupersededConsumer` (uses `volumeSeriesKey` FK lookup) → same revaluation path.

All events flow through the outbox pattern (`OutboxDomainEventPublisher` → `OutboxRelayProducer` → Kafka). Topic naming: `posval.` + event class simple name.

## Design Constraints (do NOT regress)

- **D-1:** Ledger grain = trade-leg × delivery-month block; signed qty; no interval fan-out in S1
- **D-2:** Price = expression reference; fixed price = degenerate expression
- **D-3:** Forward marks ephemeral; settlement bitemporal
- **D-11:** Unified volume: `VolumeReference → VolumeSeries × multiplier` for ALL trades
- **D-12:** S6b trade_interval_cache: optional, rebuildable, commodity-neutral
- **D-13:** `pv-domain`, `pv-persistence`, `pv-kafka`, `pv-redis`, `pv-guice` MUST remain Spring-free. Only `@jakarta.inject` and Guice annotations are permitted for DI in these modules. `pv-app` is the sole Spring-aware module. Guice is the canonical wiring; Spring `@Bean` methods in `pv-app` delegate to `injector.getInstance(...)` and must not call `new` on domain classes.
- **D-14:** Simulator-scope patterns live only in `pv-app` and must NOT be replicated in any library module. This includes: hardcoded tenant IDs, `hbm2ddl.auto=update`, in-memory cache adapters, non-RLS query paths, and any DDL not managed by Flyway. Library code (`pv-domain`, `pv-persistence`, `pv-kafka`, `pv-redis`, `pv-guice`) must assume real multi-tenancy, real Redis caches, Flyway-managed schema, and RLS enforcement — even though no production host is yet exercising them.
- **Bitemporal:** `knownFrom`/`knownTo` for knowledge-time, `validFrom`/`validTo` for business-time
- **`AbstractMaterializationJob.execute()` is `final`** — do not modify; use `SettlementRevaluationService` for sub-month revaluation

## Conventions

- Cite `FR-nnn` / `D-nn` numbers when referencing spec rules
- Reference deal: T-7788, tenant TN_0042, EPEX DE_LU wind PPA
- `docs/functional-spec/functional-spec-position-valuation-v1.0.md` is the binding spec
- `docs/context/CONTEXT-position-valuation-design.md` has design rationale
- `docs/technical-spec/improvements-dated-2026-08-07.md` tracks recent enhancements (1–9)
- `docs/adr/` contains Architecture Decision Records (pattern catalogs, design decisions)
- Test naming: `*Test.java` for unit tests, `*IT.java` for integration tests
- Kafka consumers extend `IdempotentConsumer<T>` with `alreadyProcessed()` + `process()`
- Spring Kafka listeners in `pv-app/kafka/` follow pattern: set tenant context → run in TX → ack
- `NumericPrecision` domains: `PRICE` (scale 8), `MONETARY` (scale 4), `INTERMEDIATE` (scale 10)

## Configuration

App config in `pv-app/src/main/resources/application.yml`. Key properties:
- `pv.pricing.strategy`: `expression` (default) or `rule-engine` — strategy selected at Guice `Injector` construction time in `pv-app`; do NOT re-introduce `@ConditionalOnProperty` inside the library modules
- `pv.datasource.*`: PostgreSQL connection (simulator uses local Docker Postgres on `:9432`)
- `pv.kafka.*`: Kafka bootstrap + consumer settings
- `pv.redis.*`: Redis connection (simulator can run without Redis by falling back to in-memory caches)
- `pv.seed.enabled`: Load seed data on startup (simulator convenience)

## Testing Strategy

Two layers, both real:

- **Unit tests** (per module, `*Test.java`): plain JUnit + hand-mocked ports. No container, no Spring context, no Guice injector. Domain services are testable in isolation because they only depend on port interfaces.
- **Integration tests** (`pv-integration-tests`, `*IT.java`): Testcontainers PostgreSQL 16. This is the **production-realistic** test layer. It exercises real bitemporal SQL, real partitions (where feasible), real triggers, and real Flyway migrations. **Do NOT switch integration tests to H2** — H2's PostgreSQL compat mode fails on RLS, triggers, `SET LOCAL`, `pg_partman`, native sequences with `allocationSize`, and bitemporal query patterns. Any legacy H2-based integration path is deprecated.

The `pv-app` simulator's own smoke tests (via `hbm2ddl.auto=update`) are NOT integration tests. They validate that the simulator boots, not that the library works against the real schema.

## Agent Boundaries

When Claude Code operates on this repo through subagents (functional-expert, solutions-architect, implementation-engineer, code-reviewer), the following are enforced:

**Task classification.** Before starting work, every task must be classified:
- **Library-scope:** touches `pv-domain`, `pv-persistence`, `pv-kafka`, `pv-redis`, `pv-guice`. Must respect D-13 and D-14. Must assume real multi-tenancy, real caches, Flyway schema, RLS. No shortcuts.
- **Simulator-scope:** touches only `pv-app`. May hardcode tenant, use in-memory caches, use `hbm2ddl`. Simulator-scope patterns must not leak into library modules.
- If the scope is unclear, the coder agent must ask before starting.

**Read-only for coders:** `docs/functional-spec/`, `docs/context/`, `docs/adr/`. Changes to these come only from functional-expert or solutions-architect after human approval.

**ADR required before code:** schema migrations, new Kafka topics, new domain ports, changes to the DI wiring boundary between Guice and Spring, changes to the tenant-context propagation mechanism, changes to the outbox / idempotency mechanism, and — critically — the introduction of a real production hosting layer.

**Reviewer must verify against D-1…D-14 explicitly** on every diff. A change that violates D-13 (Spring types leaking into library modules) or D-14 (simulator patterns leaking into library modules) is rejected regardless of test results. On library-scope diffs, the reviewer must also check that the code does not silently depend on `pv-app`-specific plumbing (in-memory caches, hardcoded tenant, etc.) that will not exist under a real production host.

---

## TODO — Gaps to fill in before agents ship code

The following areas are not yet documented here and agents will make wrong assumptions until they are. Fill in before pointing coder/reviewer agents at this repo.

- **Production hosting layer.** Named, scoped, and specced separately from `pv-app`. Deployment target (ECS Fargate / EKS / Lambda), tenant plumbing (JWT / gateway / header), how `SET LOCAL app.tenant_id` binds to the transaction connection, cache adapter selection, config source (SSM Parameter Store / Secrets Manager).
- **Multi-tenancy mechanism in the library.** Confirm the Hibernate strategy (SCHEMA / DATABASE / DISCRIMINATOR — V2.0 §P1 says DISCRIMINATOR). Confirm the exact `@TenantAware` parameter annotation contract (replace the placeholder `isTenantIdParam()` in `TenantInterceptor`).
- **Migration tool.** Flyway or Liquibase? Migration directory, naming convention, rollback policy. V2.0 §5 DDL becomes `V1__init.sql`.
- **Kafka serialization.** JSON via Jackson? Avro + Schema Registry? Protobuf? Header conventions for tenant, correlation ID, event version. Schema evolution policy.
- **Idempotency mechanism.** How `IdempotentConsumer<T>.alreadyProcessed()` actually works — `processed_events` table, Redis key, dedup key strategy, retention.
- **Outbox schema.** Table columns, cleanup/retention, ordering guarantees, poll interval.
- **Observability.** Actuator endpoints exposed (in the future production host, not the simulator), Micrometer, OpenTelemetry, log format (structured JSON via Logback / Logstash encoder).
- **Time and money.** `Instant` vs `OffsetDateTime` conventions. Time zone rules (gate closure — Europe/Berlin? UTC internally). Money type or `BigDecimal` + `Currency` fields.
- **HTTP layer (production host).** Spring MVC or WebFlux. OpenAPI generation. Bean Validation groups. Bearing in mind the production host is not `pv-app`.
- **Package layout.** Root package name and package structure conventions.
- **Virtual threads.** Java 21 — are virtual threads used for Kafka consumers, DB batch writers? Or platform threads only?
- **Spring Kafka version.** Pin explicitly.
- **Reconcile `pv.pricing.strategy`.** The `rule-engine` option is documented as MVEL-based, but the broader platform's rule engine work is on CEL. Confirm which is correct for this subsystem and update.
- **Two transaction wrappers.** `UnitOfWork` (pv-persistence) and `TransactionalExecutor` (pv-app) do overlapping jobs. Decide which is canonical and delete the other, or document why both must exist.
- **`SpringEntityManagerProvider.get()` fallback.** Currently leaks EntityManagers when called outside a bound transaction. Fix by throwing on unbound access, or wrap non-tx reads in an auto-closing helper.
- **`DataSourceRouter` read routing.** Reader pool is defined but no query-level dispatch is shown. Decide the read-side EM path (second EMF? explicit reader-EM injection point?) or drop the router until it's wired.
- **`BitemporalAuditListener.onPreUpdate()`.** Either implement the field-level immutability assertion or remove the listener — leaving an empty method that looks like defense-in-depth is worse than nothing.
