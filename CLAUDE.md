# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Multitenant CTRM (Commodity Trading and Risk Management) for EU power markets (~200 tenants). Covers position generation, price expression evaluation, settlement valuation, and volume series management. Java 21 / Spring Boot 3.3.5 / Hibernate 7 / PostgreSQL 16 / Kafka 3.7 KRaft / Redis 7.

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
mvn test -pl pv-kafka
mvn test -pl pv-app

# Run a single test class
mvn test -pl pv-domain -Dtest=SettlementMaterializationJobTest

# Run integration tests
mvn test -pl pv-integration-tests

# Skip tests
mvn -DskipTests clean install

# Start local infrastructure (PostgreSQL, Redis, Kafka, UIs)
cd pv-app && docker-compose up -d
```

**Local services:** PostgreSQL on `:9432` (posval/posval), Redis on `:6379`, Kafka on `:9092`, Conduktor UI on `:8081`, RedisInsight on `:5540`.

## Module Architecture

```
pv-domain              Pure domain model — zero framework deps, only @jakarta.inject
├── pv-persistence     JPA adapters (Hibernate 7, PostgreSQL), BatchWriter, OutboxDomainEventPublisher
├── pv-redis           Redis cache adapters (Lettuce) — volume, market data, forward marks
├── pv-kafka           Kafka consumers + OutboxRelayProducer (depends on pv-domain + pv-persistence)
└── pv-guice           Guice module wiring (binds ports to adapters)
     └── pv-app        Spring Boot application — controllers, DTOs, config, @KafkaListeners
          └── pv-integration-tests   End-to-end tests (H2 in-memory, no external deps)
```

**Hexagonal architecture:** Domain defines port interfaces (`pv-domain/port/`), infrastructure modules implement them. Controllers depend on service interfaces in `pv-domain/port/service/`, never on repositories directly.

**Dual DI:** Both Spring (pv-app) and Guice (pv-guice) wire the same domain services. When adding a new domain service or port binding, update both `DomainServiceConfig.java` (Spring) and `DomainModule.java` (Guice).

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
- **Bitemporal:** `knownFrom`/`knownTo` for knowledge-time, `validFrom`/`validTo` for business-time
- **`AbstractMaterializationJob.execute()` is `final`** — do not modify; use `SettlementRevaluationService` for sub-month revaluation

## Conventions

- Cite `FR-nnn` / `D-nn` numbers when referencing spec rules
- Reference deal: T-7788, tenant TN_0042, EPEX DE_LU wind PPA
- `docs/functional-spec/functional-spec-position-valuation-v1.0.md` is the binding spec
- `docs/context/CONTEXT-position-valuation-design.md` has design rationale
- `docs/technical-spec/improvements-dated-2026-08-07.md` tracks recent enhancements (1–9)
- Test naming: `*Test.java` for unit tests, `*IT.java` for integration tests
- Kafka consumers extend `IdempotentConsumer<T>` with `alreadyProcessed()` + `process()`
- Spring Kafka listeners in `pv-app/kafka/` follow pattern: set tenant context → run in TX → ack
- `NumericPrecision` domains: `PRICE` (scale 8), `MONETARY` (scale 4), `INTERMEDIATE` (scale 10)

## Configuration

App config in `pv-app/src/main/resources/application.yml`. Key properties:
- `pv.pricing.strategy`: `expression` (default) or `rule-engine` (MVEL-based)
- `pv.datasource.*`: PostgreSQL connection
- `pv.kafka.*`: Kafka bootstrap + consumer settings
- `pv.redis.*`: Redis connection
- `pv.seed.enabled`: Load seed data on startup
