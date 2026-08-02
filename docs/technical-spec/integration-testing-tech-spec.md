# Integration Testing Technical Specification

## Overview

The `pv-integration-tests` module provides end-to-end integration tests that exercise the full trade lifecycle — volume series upload, trade capture, settlement materialization — with **real JPA persistence against an H2 in-memory database**. No mocks, no Spring Boot, no Guice. All components are wired manually and all SQL executes against a real relational database.

## Architecture

```
                          IntegrationTestWiring (manual DI)
                                    |
          +-------------------------+---------------------------+
          |                         |                           |
    H2 In-Memory DB         Domain Services              In-Memory Cache
    (13 JPA entities)       (pure Java 21)            (HashMap + counters)
          |                         |                           |
    +-----+------+          +------+-------+                    |
    |            |          |              |                    |
  JPA Repos   BatchWriter  ProfileResolver  PriceEvaluator      |
    |            |          |              |                    |
    +-----+------+          +------+-------+                    |
          |                         |                           |
          +-------------------------+---------------------------+
                                    |
                           CachingMarketDataPort
                        (cache-through: cache -> DB)
```

### Why Not Spring Boot or Guice?

The production system uses Guice for DI. Rather than introducing a Spring Boot dependency or requiring a running Guice injector, the integration tests wire all components manually in `IntegrationTestWiring`. This means:

- **Zero framework overhead** — no container startup, no classpath scanning
- **Full debuggability** — set a breakpoint at any constructor and step through the entire object graph
- **Explicit dependencies** — every connection between components is visible in one file
- **Fast startup** — EMF creation + schema DDL takes ~2 seconds

## Module Structure

```
pv-integration-tests/
  pom.xml
  src/test/
    resources/
      META-INF/persistence.xml          # H2 config, schema creation, Hibernate settings
    java/com/power/posval/integration/
      EndToEndValuationIT.java           # 5 test methods covering the full pipeline
      support/
        IntegrationTestWiring.java       # Manual component wiring (replaces Guice)
        VolumeSeriesGenerator.java       # Synthetic wind/solar volume data
        MarketDataDbLoader.java          # JSON -> H2 market data loader
```

## Database Configuration

### H2 Setup (`persistence.xml`)

The H2 JDBC URL creates five schemas matching the JPA entity `@Table(schema=...)` annotations:

```
jdbc:h2:mem:pvtest;DB_CLOSE_DELAY=-1;
  INIT=CREATE DOMAIN IF NOT EXISTS "jsonb" AS CLOB\;
       CREATE SCHEMA IF NOT EXISTS "market_data"\;
       CREATE SCHEMA IF NOT EXISTS "volume_series"\;
       CREATE SCHEMA IF NOT EXISTS "position"\;
       CREATE SCHEMA IF NOT EXISTS "valuation"\;
       CREATE SCHEMA IF NOT EXISTS "trade"
```

Key Hibernate properties:
- `hibernate.hbm2ddl.auto=create-drop` — schema auto-created from JPA entities, dropped on EMF close
- `hibernate.globally_quoted_identifiers=true` — avoids H2 reserved word conflicts (e.g. `value` column)
- `hibernate.jdbc.batch_size=50` — matches production batch writer config
- `hibernate.show_sql=true` — all SQL visible in test output for debugging

### Schema-to-Entity Mapping

| Schema | Entities | Purpose |
|--------|----------|---------|
| `market_data` | FixingEntity, ForwardCurveEntity, FxRateEntity, IndexValueEntity, SpreadEntity, VolSurfaceEntity | Price inputs |
| `volume_series` | VolumeSeriesEntity, VolumeIntervalEntity, TradeIntervalCacheEntity | Generation forecasts |
| `position` | PositionLedgerEntryEntity | Trade positions (bitemporal) |
| `valuation` | SettlementCellEntity, StruckMarkEntity | Valuation outputs (bitemporal) |
| `trade` | OutboxEntity | Event outbox (not used in tests) |

### H2 Compatibility Notes

- **`jsonb` columns**: PostgreSQL's `jsonb` type doesn't exist in H2. The persistence.xml creates `CREATE DOMAIN "jsonb" AS CLOB` so Hibernate DDL succeeds. JSON is stored as plain text — sufficient for integration testing.
- **Quoted identifiers**: Several entity columns use SQL reserved words (`value`, `version`). Setting `globally_quoted_identifiers=true` ensures all identifiers are double-quoted in generated SQL.
- **Schema names**: With quoted identifiers enabled, schema names become case-sensitive. The INIT parameter quotes schema names to match Hibernate's lowercase output.

## Component Wiring (`IntegrationTestWiring`)

### EntityManager Lifecycle

```java
EntityManagerFactory emf = Persistence.createEntityManagerFactory("pv-integration-test");
ThreadLocal<EntityManager> emThreadLocal = new ThreadLocal<>();

Provider<EntityManager> emProvider = () -> {
    EntityManager em = emThreadLocal.get();
    if (em == null || !em.isOpen()) {
        em = emf.createEntityManager();
        emThreadLocal.set(em);
    }
    return em;
};
```

A single thread-local `EntityManager` is reused across all operations within a test. The `UnitOfWork` wraps operations in `begin/commit/rollback` transactions on this EM.

### Component Graph

```
EntityManagerFactory (H2)
  -> Provider<EntityManager> (thread-local lambda)
     -> BatchWriter(emProvider, DEFAULT_BATCH_SIZE=50)
     -> UnitOfWork(emProvider)
     -> JpaMarketDataRepository(emProvider)
     -> JpaVolumeSeriesRepository(emProvider, batchWriter)
     -> JpaPositionLedgerRepository(emProvider, batchWriter)
     -> JpaSettlementCellRepository(emProvider, batchWriter)

InMemoryMarketDataCache (ConcurrentHashMap + AtomicInteger counters)
ThreadLocalTenantContext (fixed to "default")

CachingMarketDataPort(cache, marketDataRepo, tenantContext)
JsonPriceExpressionRepository()  // loads EXPR-4 from classpath
DefaultPriceEvaluator(numericPrecision)
ProfileResolver(tenantNormalizedRepo, numericPrecision)
SettlementMaterializationJob(resolver, evaluator, marketData, exprRepo, cellRepo, publisher, np)
DefaultTradeCaptureHandler(ledgerRepo, eventPublisher)
TradeCapturedConsumer(seriesRepo, ledgerRepo, settlementJob)
```

### Tenant ID Normalization

`ProfileResolver` passes `ref.tradeId()` as the `tenantId` parameter to `findCurrentBySeriesKey()`, but `JpaVolumeSeriesRepository.toEntity()` hardcodes tenantId to `"default"`. The wiring wraps the volume series repository with a delegate that normalizes all tenant lookups to `"default"`.

### Event Capture

Domain events are captured in a `List<Object>` via a lambda publisher (`publishedEvents::add`). No outbox entity persistence, no Kafka. Tests inspect the list directly.

## Test Data

### Market Data (`MarketDataDbLoader`)

Parses `stub/market-data.json` (337 KB, shared with `JsonMarketDataPort`) and persists all entries via `JpaMarketDataRepository.save*()` methods:

| Data Type | Series | Count | Storage |
|-----------|--------|-------|---------|
| Fixings | EPEX_DA15 | ~8,800 | 15-min intervals, representative days across 5 years |
| Forward Curves | EEX_BASE_DE | ~120 pillars x as-of dates | Monthly pillars Jan 2025 - Dec 2029 |
| Indices | HICP_DE | ~10 entries | CPI index values (base 2023 + current) |
| FX Rates | EUR/USD | ~20 entries | Daily reference rates |

All entries are persisted with `tenantId="default"`, `versionId=1`, `qualityState=VALIDATED`.

### Volume Series (`VolumeSeriesGenerator`)

Two synthetic assets, each covering **1 month** (July 2026):

| Asset | Series Key | Asset ID | Granularity | Capacity | Pattern | Intervals |
|-------|-----------|----------|-------------|----------|---------|-----------|
| Wind | FCST-ASSET1 | ASSET-WIND-01 | 15-min | 80 MW | Sinusoidal + Gaussian noise, higher at night | 2,976 |
| Solar | FCST-ASSET2 | ASSET-SOLAR-01 | 30-min | 45 MW | Daytime bell curve (06:00-20:00), zero at night | 1,488 |

Both are `SeriesType.FORECAST`, `QualityState.CURRENT`, saved via `JpaVolumeSeriesRepository.save()` which batch-writes interval entities.

### Trades

6 trades created (3 per asset):

| Trade ID | Asset | Multiplier | Delivery | Expression |
|----------|-------|------------|----------|------------|
| T-ASSET1-1 | ASSET-WIND-01 | 0.3 | Jul 2026 - Jul 2027 | EXPR-4 |
| T-ASSET1-2 | ASSET-WIND-01 | 0.3 | Jul 2026 - Jul 2027 | EXPR-4 |
| T-ASSET1-3 | ASSET-WIND-01 | 0.4 | Jul 2026 - Jul 2027 | EXPR-4 |
| T-ASSET2-1 | ASSET-SOLAR-01 | 0.3 | Jul 2026 - Jul 2027 | EXPR-4 |
| T-ASSET2-2 | ASSET-SOLAR-01 | 0.3 | Jul 2026 - Jul 2027 | EXPR-4 |
| T-ASSET2-3 | ASSET-SOLAR-01 | 0.4 | Jul 2026 - Jul 2027 | EXPR-4 |

Multipliers sum to 1.0 per asset (full capacity allocation).

## End-to-End Flow

The main test (`endToEnd_volumeUpload_tradeCapture_settlement`) executes 6 phases:

### Phase 1: Load Market Data

```
stub/market-data.json
    -> MarketDataDbLoader.load()
        -> JpaMarketDataRepository.saveFixing()      x ~8,800
        -> JpaMarketDataRepository.saveForwardCurve() x ~120
        -> JpaMarketDataRepository.saveIndex()        x ~10
        -> JpaMarketDataRepository.saveFxRate()        x ~20
    -> H2: INSERT INTO market_data.fixing ...
```

All saves happen within a single `UnitOfWork` transaction. The JSON parsing reuses the same `extractObject`/`extractKeys`/`extractKeyValuePairs` approach as `JsonMarketDataPort`.

**Assertion**: `SELECT COUNT(*) FROM FixingEntity > 0`

### Phase 2: Create Volume Series

```
VolumeSeriesGenerator.createAsset1()
    -> DefaultVolumeSeries.builder()...build()  (2,976 intervals)
    -> JpaVolumeSeriesRepository.save()
        -> em.persist(VolumeSeriesEntity)
        -> batchWriter.writeAll(VolumeIntervalEntity[])
            -> persist + flush/clear every 50 rows
    -> H2: INSERT INTO volume_series.volume_series ...
           INSERT INTO volume_series.volume_interval ... x 2,976

VolumeSeriesGenerator.createAsset2()
    -> (same flow, 1,488 intervals)
```

Each asset is saved in its own transaction.

**Assertion**: `findCurrentBySeriesKey()` returns both series.

### Phase 3: Capture Trades

For each of 6 trades:

```
TradeCapture command
    -> DefaultTradeCaptureHandler.handle()
        -> DeliveryPeriod.toMonthBlocks()  (12 monthly blocks)
        -> For each month:
            -> PositionLedgerEntry.builder()...build()
            -> ledgerRepo.save()
                -> em.persist(PositionLedgerEntryEntity)
        -> eventPublisher.publish(PositionCaptured)
    -> H2: INSERT INTO position.position_ledger_entry ... x 12
```

Each trade produces 12 ledger entries (Jul 2026 through Jun 2027). The `PositionCaptured` event is captured in the `publishedEvents` list.

**Assertion**: 12 entries per trade, event published.

### Phase 4: Settlement Materialization

For each of 6 `PositionCaptured` events:

```
TradeCapturedConsumer.handle(event)
    -> alreadyProcessed()
        -> seriesRepo.existsByTradeIdAndTradeVersion()  -> false
    -> process()
        -> ledgerRepo.findCurrentByTradeLeg()  -> 12 entries
        -> For each entry (monthly block):
            -> settlementJob.execute(entry, deliveryRange)
                -> ProfileResolver.resolve()
                    -> seriesRepo.findCurrentBySeriesKey()
                    -> VolumeFilterMapper.filterAndMap()
                        -> Filter intervals within delivery range
                        -> Apply multiplier
                    -> Returns VolumeRecord[] for each 15/30-min interval
                -> For each VolumeRecord:
                    -> PriceEvaluator.evaluate(EXPR-4, interval)
                        -> CachingMarketDataPort.lookupFixing()
                            -> cache miss -> JpaMarketDataRepository.findFixing() -> cache put
                            OR
                            -> cache hit -> return cached value
                        -> CachingMarketDataPort.lookupIndex()
                            -> (same cache-through pattern)
                        -> Evaluate expression tree:
                            ConditionalGate(EPEX < 0, then=0, else=
                              Clamp(floor=38, cap=110,
                                Escalate(base=72,
                                  Divide(HICP_current, HICP_base))))
                        -> Result: ~74.38 EUR/MWh (CPI-escalated)
                    -> buildResult()
                        -> amount = price x energy
                        -> SettlementCell record
                -> flushResults()
                    -> cellRepo.saveAll(cells)
                        -> batchWriter.writeAll(SettlementCellEntity[])
                    -> eventPublisher.publishAll(SettlementComputed events)
    -> H2: INSERT INTO valuation.settlement_cell ... x N
```

Only July 2026 produces settlement cells (the volume series covers only 1 month). Other months have no volume intervals, so `resolve()` returns an empty list and no cells are created.

**Result**: 13,392 settlement cells persisted (2,976 wind intervals x 3 trades + 1,488 solar intervals x 3 trades = 13,392).

### Phase 5: Verify Database State

Direct JPQL queries against H2:

```sql
SELECT COUNT(c) FROM SettlementCellEntity c
-- Result: 13,392

SELECT c FROM SettlementCellEntity c ORDER BY c.intervalStart
-- Sample cells verified:
--   price BETWEEN 38 AND 110 (collar bounds)
--   amount >= 0
--   activeLeaves contains "BASE_PRICE_72", "HICP_DE_CURRENT"
```

### Phase 6: Verify Cache Behavior

```
cache.cacheHits   = 23,807  (repeated lookups for same fixing/index)
cache.cacheMisses =  2,977  (first lookup per unique key)
cache.store.size  =  2,977  (one entry per unique cache key)
```

The cache-through pattern is verified: first lookup misses and queries H2, subsequent lookups for the same key hit the in-memory cache.

## Price Expression: EXPR-4 (CPI-Escalated Collar)

The expression tree evaluated for each interval:

```
ConditionalGate
  condition: MarketDataLeaf("EPEX_DA15") < 0       // negative price protection
  ifTrue:    ConstantLeaf(0)                        // zero price if EPEX negative
  ifFalse:   Clamp                                  // collar
               floor: ConstantLeaf(38)              // floor at 38 EUR/MWh
               cap:   ConstantLeaf(110)             // cap at 110 EUR/MWh
               inner: Escalate                      // CPI escalation
                        base: ConstantLeaf(72)      // base price 72 EUR/MWh
                        ratio: Divide
                                 num: IndexLeaf("HICP_DE", "CURRENT")    // 112.30
                                 den: IndexLeaf("HICP_DE", "BASE_2023")  // 108.70

Evaluation: 72 * (112.30 / 108.70) = 74.38 EUR/MWh
Collar:     38 <= 74.38 <= 110  -> 74.38 (no clamping)
Gate:       EPEX >= 0           -> passes through
Result:     74.38 EUR/MWh for every interval
```

Active leaves tracked: `EPEX_DA15_GATE`, `BASE_PRICE_72`, `HICP_DE_CURRENT`, `HICP_DE_BASE_2023`.
Floor/cap leaves are **inactive** (price is inside the collar).

## Test Methods

| Test | What It Verifies |
|------|-----------------|
| `endToEnd_volumeUpload_tradeCapture_settlement` | Full 6-phase pipeline: market data -> volume -> trades -> settlement cells persisted in H2 |
| `tradeMultipliers_coverFullAssetCapacity` | 0.3 + 0.3 + 0.4 = 1.0 of asset volume |
| `cachingMarketDataPort_hitsOnRepeatedLookups` | First lookup = cache miss + DB query; second = cache hit |
| `settlementCells_priceWithinCollarBounds` | All persisted prices between 38 and 110 EUR/MWh |
| `databasePersistence_cellsQueryableViaEntityManager` | Direct JPQL confirms cells queryable by tenant in H2 |

## Running the Tests

```bash
# Run integration tests only
mvn clean test -pl pv-integration-tests -am

# Run a specific test
mvn test -pl pv-integration-tests -Dtest=EndToEndValuationIT#endToEnd_volumeUpload_tradeCapture_settlement
```

### Debugging

1. **SQL trace**: `hibernate.show_sql=true` is on by default — all INSERT/SELECT statements are printed.
2. **Cache-through breakpoint**: Set breakpoint at `CachingMarketDataPort.lookupFixing()` — step through cache miss -> DB query -> cache populate.
3. **Price evaluation breakpoint**: Set breakpoint at `DefaultPriceEvaluator.evaluate()` — inspect the expression tree traversal and active leaf tracking.
4. **Settlement construction breakpoint**: Set breakpoint at `SettlementMaterializationJob.buildResult()` — inspect price, energy, amount, active leaves.

### Performance

| Phase | Duration | Notes |
|-------|----------|-------|
| H2 schema creation | ~2s | 13 entities, 5 schemas, sequences, indexes |
| Phase 1 (market data) | ~2s | ~9,000 inserts |
| Phase 2 (volume series) | ~1s | ~4,500 interval inserts via batch writer |
| Phase 3 (trade capture) | <1s | 72 ledger entries |
| Phase 4 (settlement) | ~15s | 13,392 cells: resolve + evaluate + persist |
| **Total** | **~21s** | Single-threaded, H2 in-process |
