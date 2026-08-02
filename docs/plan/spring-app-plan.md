# Plan: End-to-End Integration Test Module with Real JPA Persistence

## Context

The user wants to simulate a complete trade lifecycle — volume series upload, trade creation, valuation — with **real database persistence** and see data flow through all layers. The existing system uses Guice DI (not Spring Boot). All JPA adapters accept `Provider<EntityManager>` via constructor injection, and all domain services use `@Inject` (jakarta.inject).

Rather than migrating the entire DI framework to Spring Boot, we create a **new `pv-integration-tests` module** that wires all real JPA adapters against an **H2 in-memory database** and uses an **in-memory MarketDataCache** (with hit/miss tracking). This gives full debuggable end-to-end flow with actual SQL persistence without framework migration.

## What This Delivers

- Real SQL execution via Hibernate + H2 (schema auto-created from JPA entities)
- Real `BatchWriter` flush/clear cycles
- Real JPQL queries (fixings, forward curves, floor semantics)
- Market data loaded from `stub/market-data.json` into H2 via `JpaMarketDataRepository`
- Volume series generated programmatically (matching FiveYearTradeIntegrationTest pattern)
- 6 trades (3 per asset, multipliers 0.3/0.3/0.4) using EXPR-4 CPI-escalated collar
- Full valuation pipeline: volume × price expression → settlement cells → persisted to H2
- Cache hit/miss counters to verify `CachingMarketDataPort` behavior
- Debuggable: breakpoints at any layer show real data

## New Module: `pv-integration-tests`

### New Files (6)

| File | Purpose |
|------|---------|
| `pv-integration-tests/pom.xml` | Module POM: depends on pv-domain, pv-persistence, pv-kafka; H2 test dep |
| `pv-integration-tests/src/test/resources/META-INF/persistence.xml` | H2 JPA config with `create-drop` + schema init |
| `pv-integration-tests/src/test/java/.../support/IntegrationTestWiring.java` | Manual component wiring (replaces Guice modules) |
| `pv-integration-tests/src/test/java/.../support/VolumeSeriesGenerator.java` | Generates 15-min and 30-min volume series programmatically |
| `pv-integration-tests/src/test/java/.../support/MarketDataDbLoader.java` | Parses `stub/market-data.json` → saves via `JpaMarketDataRepository` |
| `pv-integration-tests/src/test/java/.../EndToEndValuationIT.java` | Main integration test (6 phases) |

### Modified Files (1)

| File | Change |
|------|--------|
| `pom.xml` (root) | Add `<module>pv-integration-tests</module>` |

## Detailed Design

### 1. `persistence.xml` — H2 with Multi-Schema Support

H2 JDBC URL uses `INIT` to create schemas matching JPA entity `@Table(schema=...)` annotations:
```
jdbc:h2:mem:pvtest;DB_CLOSE_DELAY=-1;
  INIT=CREATE SCHEMA IF NOT EXISTS market_data\;
       CREATE SCHEMA IF NOT EXISTS volume_series\;
       CREATE SCHEMA IF NOT EXISTS position_ledger\;
       CREATE SCHEMA IF NOT EXISTS valuation
```

Properties: `hibernate.hbm2ddl.auto=create-drop`, `hibernate.show_sql=true`, `hibernate.jdbc.batch_size=50`, `hibernate.order_inserts=true`.

Lists all 11+ JPA entity classes from pv-persistence.

### 2. `IntegrationTestWiring` — Manual DI (No Guice, No Spring)

Creates H2-backed `EntityManagerFactory`, then constructs all components manually:

```
EntityManagerFactory (H2)
  → Provider<EntityManager> (lambda: emf.createEntityManager())
  → BatchWriter(emProvider, 50)
  → UnitOfWork(emProvider)

JPA Repositories (real):
  → JpaVolumeSeriesRepository(emProvider, batchWriter)
  → JpaPositionLedgerRepository(emProvider, batchWriter)
  → JpaSettlementCellRepository(emProvider, batchWriter)
  → JpaMarketDataRepository(emProvider)

Cache (in-memory with tracking):
  → InMemoryMarketDataCache (HashMap-backed, tracks hits/misses)

Domain Services:
  → TenantContext (fixed "TN_TEST")
  → CachingMarketDataPort(cache, marketDataRepo, tenantContext)
  → JsonPriceExpressionRepository() (loads EXPR-4 from classpath)
  → DefaultPriceEvaluator(numericPrecision)
  → ProfileResolver(volumeSeriesRepo, numericPrecision)
  → SettlementMaterializationJob(resolver, evaluator, marketData, exprRepo, cellRepo, publisher, precision)
  → DefaultTradeCaptureHandler(ledgerRepo, eventPublisher)
  → TradeCapturedConsumer(seriesRepo, ledgerRepo, settlementJob)
```

The `InMemoryMarketDataCache` implements `MarketDataCache` with a `ConcurrentHashMap` and `AtomicInteger` counters for `cacheHits` and `cacheMisses`.

Provides `UnitOfWork` for wrapping operations in transactions and a helper `inTransaction(Runnable)` method.

### 3. `VolumeSeriesGenerator` — Programmatic Volume Data

Generates synthetic data (same approach as `FiveYearTradeIntegrationTest`):

- **Asset1**: `seriesKey="FCST-ASSET1"`, `assetId="ASSET-WIND-01"`, 10 years starting Jul 2026, 15-min granularity, 80 MW capacity, wind pattern (sinusoidal + noise)
- **Asset2**: `seriesKey="FCST-ASSET2"`, `assetId="ASSET-SOLAR-01"`, 10 years starting Jul 2026, 30-min granularity, 45 MW capacity, solar pattern (daytime peak)

Saves via `JpaVolumeSeriesRepository.save()` within a transaction. Each series is `SeriesType.FORECAST`, `QualityState.CURRENT`.

**Performance optimization**: For the fast test, generates only **1 month** (Jul 2026) of intervals per asset. The `@Tag("slow")` variant generates the full 10 years.

### 4. `MarketDataDbLoader` — JSON → Database

Reuses the JSON parsing methods from `JsonMarketDataPort` (static `extractObject`, `extractKeys`, `extractKeyValuePairs`) to parse `stub/market-data.json`. For each entry, calls the appropriate save method on `JpaMarketDataRepository`:

- Fixings → `saveFixing(tenantId, series, instant, lookup)`
- Forward curves → `saveForwardCurve(tenantId, series, pillar, asOfDate, lookup)`
- Indices → `saveIndex(tenantId, series, refMonthExpression, lookup)`
- FX rates → `saveFxRate(tenantId, pair, referenceDate, lookup)`

All saves happen within a single transaction.

### 5. `EndToEndValuationIT` — Main Test

```
@BeforeEach: IntegrationTestWiring.create()

Phase 1: Load Market Data
  → unitOfWork.run(() -> MarketDataDbLoader.load(marketDataRepo, tenantId))
  → Assert: EM query confirms fixings in DB

Phase 2: Create Volume Series (2 assets)
  → unitOfWork.run(() -> VolumeSeriesGenerator.createAsset1(seriesRepo))
  → unitOfWork.run(() -> VolumeSeriesGenerator.createAsset2(seriesRepo))
  → Assert: findCurrentBySeriesKey returns both

Phase 3: Create 6 Trades (3 per asset, multipliers 0.3/0.3/0.4)
  For each (asset, multiplier):
    → TradeCapture command:
        tradeId = "T-ASSET1-1" etc
        priceExpressionId = EXPR-4 UUID
        deliveryPeriod = Jul 2026 → Jul 2034 (8 years)
        volumeSeriesKey = "FCST-ASSET1" or "FCST-ASSET2"
        multiplier = 0.3, 0.3, or 0.4
        assetId = "ASSET-WIND-01" or "ASSET-SOLAR-01"
    → unitOfWork.run(() -> tradeCaptureHandler.handle(command))
    → Assert: ledger entries created (96 months per trade)
    → Collect PositionCaptured events

Phase 4: Trigger Valuation (process first month only for speed)
  For the first PositionCaptured event of each trade:
    → unitOfWork.run(() -> tradeCapturedConsumer.handle(event))
    → Settlement cells created in H2

Phase 5: Verify Database State
  → Query settlement cells via EntityManager
  → Assert: cells exist with correct price (~74.38 EUR/MWh from CPI escalation)
  → Assert: amount = price × energy
  → Assert: activeLeaves contain "BASE_PRICE_72", "HICP_DE_CURRENT"
  → Assert: multipliers applied (trade with 0.3 → 30% of asset volume)

Phase 6: Verify Cache Behavior
  → Assert: cache.cacheHits > 0 (repeated lookups hit cache)
  → Assert: cache.cacheMisses > 0 (first lookups miss)
  → Assert: cache.store.size() > 0 (cache populated)

@AfterEach: Close EntityManagerFactory
```

### Test Methods

| Test | What it verifies |
|------|-----------------|
| `endToEnd_volumeUpload_tradeCapture_settlement` | Full pipeline: market data → volume → trades → settlement cells persisted |
| `tradeMutipliers_coverFullAssetCapacity` | 0.3 + 0.3 + 0.4 = 1.0 of asset volume |
| `cachingMarketDataPort_hitsOnRepeatedLookups` | First lookup = cache miss + DB query; second = cache hit |
| `settlementCells_priceWithinCollarBounds` | All prices between 38 and 110 EUR/MWh |
| `databasePersistence_cellsQueryableViaEntityManager` | Direct JPQL confirms cells in H2 |

## Implementation Order

1. Add `pv-integration-tests` module to root POM
2. Create `pv-integration-tests/pom.xml`
3. Create `persistence.xml` for H2
4. Create `IntegrationTestWiring`
5. Create `VolumeSeriesGenerator`
6. Create `MarketDataDbLoader`
7. Create `EndToEndValuationIT`
8. Run and verify

## Key Reference Files

- `pv-kafka/.../FiveYearTradeIntegrationTest.java` — volume generation + wiring pattern
- `pv-persistence/.../JpaVolumeSeriesRepository.java` — JPA adapter constructor signature
- `pv-persistence/.../batch/UnitOfWork.java` — transaction boundary management
- `pv-persistence/.../provider/EntityManagerFactoryProvider.java` — EMF creation pattern
- `pv-domain/.../service/stub/JsonMarketDataPort.java` — JSON parsing (extractObject, extractKeys, extractKeyValuePairs)
- `pv-domain/.../command/TradeCapture.java` — 16-field record constructor
- `pv-domain/.../service/CachingMarketDataPort.java` — cache-through pattern

## Verification

1. `mvn clean test -pl pv-integration-tests -am` — integration tests pass
2. Console shows Hibernate SQL (INSERT INTO market_data.fixing..., SELECT FROM...)
3. Set breakpoint at `CachingMarketDataPort.lookupFixing()` → step through cache miss → DB query → cache populate
4. Set breakpoint at `SettlementMaterializationJob.buildResult()` → inspect price, energy, amount
5. After test: verify `cache.cacheHits > 0` in assertions
