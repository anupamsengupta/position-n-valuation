# Developer Usage Walkthrough — Position & Valuation System

**Audience:** Entry-level developers joining the team. This document walks you through the entire codebase — how it's organized, how data flows from trade capture to final valuation, and how to follow the code when debugging or adding features.

**How to read this:** Start from Section 1 and read linearly. Each section builds on the previous one. Code paths are given as `module/package/ClassName.method()` so you can open the exact file.

---

## 1. What Does This System Do?

In one sentence: **it takes electricity trades, figures out how much power flows in each 15-minute slot, looks up or calculates the price for each slot, and multiplies quantity x price to get a money amount.**

That's it. Everything else is infrastructure to make that multiplication happen correctly, quickly, and auditably across 200 customer companies and millions of time slots.

The three core questions the system answers:

| Question | Where the answer lives |
|---|---|
| "How much power?" | `VolumeSeries` (forecasts and profiles) |
| "At what price?" | `PriceExpression` tree + `MarketDataPort` lookups |
| "How much money?" | `SettlementCell`, `ForwardMark`, `StruckMark` |

---

## 2. Project Layout — The 6 Modules

Open the root folder. You'll see 6 Maven modules:

```
position-and-valuation/
├── pv-domain/          ← The brain. All business logic lives here.
├── pv-persistence/     ← Database adapters (PostgreSQL via JPA/Hibernate)
├── pv-redis/           ← Cache adapters (Redis via Lettuce)
├── pv-kafka/           ← Event consumers and producers (Kafka)
├── pv-guice/           ← Dependency injection wiring (Google Guice)
└── pom.xml             ← Parent POM tying it all together
```

### The Golden Rule: Dependencies Flow Inward

```
pv-guice  ──→  pv-kafka  ──→  pv-domain  ←──  pv-persistence
                                   ↑
                               pv-redis
```

- `pv-domain` depends on **nothing** (except `jakarta.inject` annotations). It has zero knowledge of databases, Redis, or Kafka.
- `pv-persistence`, `pv-redis`, `pv-kafka` all depend on `pv-domain` — they implement the interfaces (ports) that the domain defines.
- `pv-guice` depends on everything — it wires the interfaces to their implementations.

**Why this matters:** When you're debugging business logic, you only need to look inside `pv-domain`. When you're debugging "why isn't this getting saved to the database", look at `pv-persistence`. When you're debugging "why didn't this event trigger", look at `pv-kafka`.

---

## 3. The Domain Layer — Where the Business Logic Lives

Open `pv-domain/src/main/java/com/power/posval/domain/`. You'll see:

```
domain/
├── model/              ← Data structures (what things ARE)
│   ├── expression/     ← Price formula tree (13 node types)
│   └── value/          ← Small value objects (Money, SeriesKey, etc.)
├── port/               ← Interfaces the outside world must implement
│   ├── cache/          ← Cache contracts
│   ├── datasource/     ← Database routing
│   ├── event/          ← Event publishing contract
│   ├── marketdata/     ← Market data lookups
│   └── repository/     ← Database access contracts
├── service/            ← Business logic (what the system DOES)
│   └── stub/           ← JSON-backed fakes for development
├── command/            ← Incoming requests (TradeCapture, TradeAmend, etc.)
├── event/              ← Things that happened (PositionCaptured, etc.)
└── exception/          ← Domain-specific errors
```

### 3.1 Models — The Nouns

These are the data containers. They don't do anything — they just hold information.

#### The Position Ledger Entry

**File:** `model/PositionLedgerEntry.java`

This is the most important record in the system. When a trader books a deal, the system creates one `PositionLedgerEntry` per delivery month. A 12-month deal creates 12 entries.

Key fields you'll encounter constantly:

```
PositionLedgerEntry
├── id                    ← unique UUID
├── tenantId              ← which customer company ("TN_0042")
├── tradeId               ← the trade ("T-7788")
├── tradeLegId            ← which side/leg of the trade ("LEG-1")
├── deliveryRange         ← which month this entry covers (e.g., March 2025)
├── quantity              ← how much power (signed: + = buying, - = selling)
├── priceExpressionId     ← UUID pointing to the price formula
├── volumeSeriesKey       ← key to find the volume data
├── validFrom / validTo   ← "when was this TRUE in the real world?"
├── knownFrom / knownTo   ← "when did the SYSTEM learn about this?"
└── status                ← ACTIVE, SUPERSEDED, or CANCELLED
```

**The bitemporal twist:** Every entry has TWO time dimensions. `validFrom/To` tracks business reality ("this trade was effective from March 1"). `knownFrom/To` tracks system knowledge ("we recorded this at 10:05 AM on Feb 28"). This lets you answer questions like "what did we think we knew last Tuesday?" — crucial for regulatory audits.

#### Volume Series

**Files:** `model/VolumeSeries.java` (interface), `model/DefaultVolumeSeries.java` (implementation)

A `VolumeSeries` is a collection of time-sliced intervals telling you "how much power flows in each 15-minute slot."

```
VolumeSeries
├── seriesKey             ← stable external key (e.g., "FCST-WP-NORDSEE")
├── seriesType            ← FORECAST (shared per wind park) or PROFILE (per trade)
├── deliveryPeriod        ← what time window this covers
├── intervals             ← the actual data: a sorted set of VolumeInterval records
│   └── VolumeInterval
│       ├── intervalStart ← e.g., 2025-03-01T00:00:00Z
│       ├── intervalEnd   ← e.g., 2025-03-01T00:15:00Z
│       ├── volume        ← MW (power capacity)
│       └── energy        ← MWh (energy over the interval)
└── versionId             ← version number (increments on update)
```

**The unified pattern:** Every trade resolves volume the same way:

```
trade_volume = volume_interval.volume  x  multiplier
```

- A wind PPA uses a shared forecast with multiplier 0.3 (the trade owns 30% of the wind park)
- A day-ahead trade uses a dedicated profile with multiplier 1.0

Same code path, different data. This is a key design decision (D-11).

#### Settlement Cell

**File:** `model/SettlementCell.java`

The final output — quantity x price = money:

```
SettlementCell
├── positionId           ← which position this settles
├── intervalStart/End    ← which 15-minute slot
├── price                ← EUR/MWh for this slot
├── volumeMw / volumeMwh ← how much power/energy
├── amount               ← price x energy = money
├── activeLeaves         ← which parts of the price formula were used
├── inputVersionSet      ← version IDs of all market data inputs
└── validFrom/To, knownFrom/To  ← bitemporal axes
```

#### Price Expression Tree

**Files:** `model/expression/PriceExpression.java` and 13 type files

Prices aren't just numbers — they're formulas. A simple fixed-price trade has:

```java
new ConstantLeaf("FIXED_85", BigDecimal.valueOf(85.00), "EUR/MWh")
```

A complex wind PPA with a collar has:

```java
new Clamp(
    new ConstantLeaf("FLOOR", 40.00, "EUR/MWh"),    // floor
    new ConstantLeaf("CAP", 120.00, "EUR/MWh"),      // cap
    new MarketDataLeaf("EPEX", "EPEX_DA15", ...)     // market price
)
// Result: max(40, min(120, market_price))
```

The `PriceExpression` is a **sealed interface** — Java 21 ensures the compiler checks you've handled every type. The 13 types are:

| Type | What it does | Example |
|---|---|---|
| `ConstantLeaf` | Fixed number | 85.00 EUR/MWh |
| `MarketDataLeaf` | Look up a market price | EPEX day-ahead 15-min |
| `IndexLeaf` | Look up an inflation index | HICP-DE |
| `Add` / `Subtract` | Arithmetic | base + spread |
| `Multiply` / `Divide` | Arithmetic | price x quantity |
| `Clamp` | Floor/cap | max(floor, min(cap, inner)) |
| `Escalate` | Index escalation | base x (CPI_current / CPI_base) |
| `ConditionalGate` | If/else | if price < 0 then 0 else price |
| `ConditionalPassThrough` | Pass value through | if condition then gateValue else inner |
| `TimeAverage` | Average over window | monthly average price |
| `FxConvert` | Currency conversion | EUR price x USD/EUR rate |

### 3.2 Ports — The Contracts with the Outside World

Ports are interfaces in `domain/port/`. The domain says "I need something that can do X" — and the infrastructure modules provide implementations.

**Key ports you'll encounter:**

| Port Interface | What it does | Implemented by |
|---|---|---|
| `VolumeSeriesRepository` | Save/load volume series | `JpaVolumeSeriesRepository` |
| `PositionLedgerRepository` | Save/load position entries | `JpaPositionLedgerRepository` |
| `SettlementCellRepository` | Save/load settlement cells | `JpaSettlementCellRepository` |
| `StruckMarkRepository` | Save/load EOD marks | `JpaStruckMarkRepository` |
| `PriceExpressionRepository` | Load price formulas | `JsonPriceExpressionRepository` (stub) |
| `MeteredActualRepository` | Load meter readings | `JsonMeteredActualRepository` (stub) |
| `MarketDataPort` | Look up market prices | `JsonMarketDataPort` (stub) |
| `VolumeCache` | Cache volume lookups | `RedisVolumeCache` |
| `ForwardMarkStore` | Store ephemeral marks | `RedisForwardMarkStore` |
| `DomainEventPublisher` | Publish events | `OutboxDomainEventPublisher` |
| `DependencyIndex` | Track what depends on what | `JpaDependencyIndex` |
| `RollupRepository` | Aggregated views | `JpaRollupRepository` |

**Stub implementations:** Three external services (`MarketDataPort`, `PriceExpressionRepository`, `MeteredActualRepository`) have JSON-file-backed stubs in `service/stub/`. These load test data from `src/main/resources/stub/*.json` so you can run the system without real market data or expression databases. They'll be swapped for real implementations later.

### 3.3 Services — The Verbs

This is where things happen. Open `domain/service/` and you'll see the core business operations.

#### Volume Resolution

**Files:** `VolumeResolver.java`, `ProfileResolver.java`, `ForecastResolver.java`

`VolumeResolver` is a **sealed interface** with exactly two implementations:

```java
public sealed interface VolumeResolver permits ProfileResolver, ForecastResolver {
    List<VolumeRecord> resolve(VolumeReference ref,
                                DeliveryRange intervalRange,
                                ResolutionPurpose purpose);
}
```

- `ProfileResolver` — reads from a per-trade volume series. Used for day-ahead and bilateral trades.
- `ForecastResolver` — reads from a per-asset shared series. If purpose is SETTLEMENT and metered data exists, it uses actual meter readings instead of forecasts.

Both return `List<VolumeRecord>` — each record is one 15-minute slot with volume, energy, and the multiplier applied.

#### Price Evaluation

**Files:** `PriceEvaluator.java`, `DefaultPriceEvaluator.java`

The price evaluator walks the `PriceExpression` tree recursively:

```java
public PriceResolution evaluate(PriceExpression expr,
                                 DeliveryPeriod interval,
                                 ResolutionPurpose purpose,
                                 MarketDataPort marketData)
```

It uses Java 21's exhaustive `switch` over the sealed interface:

```java
return switch (expr) {
    case ConstantLeaf c  -> c.value();
    case MarketDataLeaf m -> marketData.lookupFixing(m.series(), interval.start());
    case Add a           -> eval(a.left()) + eval(a.right());
    case Clamp cl        -> max(eval(cl.min()), min(eval(cl.max()), eval(cl.inner())));
    // ... handles all 13 types
};
```

As it walks the tree, it tracks:
- **`activeLeaves`** — which leaf nodes actually contributed to the result (important for dependency tracking)
- **`inputVersionSet`** — version IDs of every market data input used (important for reproducibility)

The result is a `PriceResolution(value, activeLeaves, inputVersionSet)`.

#### Materialization Jobs — The Main Event

**Files:** `AbstractMaterializationJob.java`, `SettlementMaterializationJob.java`, `ForwardMarkJob.java`, `EodStrikeJob.java`

This is where quantity x price = money happens. It uses the **Template Method pattern**:

```java
public abstract class AbstractMaterializationJob {

    // This method is FINAL — subclasses can't change the flow
    public final void execute(PositionLedgerEntry position,
                               DeliveryRange intervalRange) {
        // Step 1: Get volume data
        List<VolumeRecord> volumes = resolveVolume(position, intervalRange);

        // Step 2: For each time slot...
        for (VolumeRecord vol : volumes) {
            // Step 2a: Get the price
            PriceResolution price = evaluatePrice(position.priceExpressionId(), interval);

            // Step 2b: Write the result
            writeResult(position, vol, price);
        }
    }

    // Subclasses fill in these three "hooks":
    protected abstract List<VolumeRecord> resolveVolume(...);
    protected abstract PriceResolution evaluatePrice(...);
    protected abstract void writeResult(...);
}
```

Three concrete implementations:

| Job | Purpose | What it writes | Persistence |
|---|---|---|---|
| `SettlementMaterializationJob` | Settlement (actual money) | `SettlementCell` | Durable, bitemporal, append-only |
| `ForwardMarkJob` | Forward valuation (projected value) | `ForwardMark` | Ephemeral (Redis only, overwritten) |
| `EodStrikeJob` | End-of-day snapshot | `StruckMark` | Durable, immutable, append-only |

**Following the code for settlement:**

1. `SettlementMaterializationJob.resolveVolume()` — calls `volumeResolver.resolve()` with purpose `SETTLEMENT`
2. `SettlementMaterializationJob.evaluatePrice()` — loads the price expression from `priceExpressionRepo`, then calls `priceEvaluator.evaluate()` with purpose `SETTLEMENT`
3. `SettlementMaterializationJob.writeResult()` — calculates `amount = price x energy`, creates a `SettlementCell`, saves it, and publishes a `SettlementComputed` event

#### Trade Handling

**Files:** `DefaultTradeCaptureHandler.java`, `DefaultTradeAmendHandler.java`, `DefaultTradeCancelHandler.java`

When a new trade arrives:

1. `DefaultTradeCaptureHandler.handle(TradeCapture cmd)` is called
2. It decomposes the trade's delivery period into monthly blocks (e.g., a 3-month trade becomes 3 `PositionLedgerEntry` records)
3. It saves the entries to the ledger
4. It publishes a `PositionCaptured` event

Amendments and cancellations follow the same pattern but handle bitemporal versioning — they close old entries (set `knownTo`) and create new ones.

---

## 4. The Event-Driven Cascade — How Everything Connects

The system uses an event-driven pipeline. Here's the full flow from trade capture to final settlement:

```
STEP 1: Trade Capture
  ┌─────────────────────────────────────────────────┐
  │  TradeCapture command arrives                    │
  │  → DefaultTradeCaptureHandler.handle()           │
  │  → Creates PositionLedgerEntry records           │
  │  → Publishes "PositionCaptured" event            │
  │  → Event saved to Outbox table (same transaction)│
  └───────────────────────┬─────────────────────────┘
                          │
STEP 2: Event Relay       │
  ┌───────────────────────▼─────────────────────────┐
  │  OutboxRelayProducer polls outbox table           │
  │  → Sends event to Kafka topic                    │
  │  → Marks outbox row as published                 │
  └───────────────────────┬─────────────────────────┘
                          │
STEP 3: Consumer          │
  ┌───────────────────────▼─────────────────────────┐
  │  TradeCapturedConsumer receives event             │
  │  → Checks idempotency (already processed?)       │
  │  → Loads position entries from ledger             │
  │  → Calls SettlementMaterializationJob.execute()  │
  └───────────────────────┬─────────────────────────┘
                          │
STEP 4: Materialization   │
  ┌───────────────────────▼─────────────────────────┐
  │  SettlementMaterializationJob.execute()           │
  │  For each 15-minute slot:                        │
  │    1. Resolve volume (ProfileResolver)            │
  │    2. Load price expression (repository)          │
  │    3. Evaluate price (tree walk)                  │
  │    4. Calculate: amount = price x energy          │
  │    5. Save SettlementCell                         │
  │    6. Publish "SettlementComputed" event          │
  └───────────────────────┬─────────────────────────┘
                          │
STEP 5: Downstream        │
  ┌───────────────────────▼─────────────────────────┐
  │  SettlementPublishedConsumer                      │
  │  → Triggers rollup refresh (aggregated views)    │
  │  → Updates dependency index                      │
  └─────────────────────────────────────────────────┘
```

### The Outbox Pattern

Events aren't sent directly to Kafka. Instead:

1. The domain operation and the event are saved **in the same database transaction** (into the `outbox` table)
2. A background poller (`OutboxRelayProducer`) reads unpublished events and sends them to Kafka
3. After Kafka acknowledges, the outbox row is marked as published

This guarantees **no events are lost**, even if Kafka is temporarily down. The event will be retried later.

### Idempotent Consumers

Every Kafka consumer extends `IdempotentConsumer<E>`:

```java
public abstract class IdempotentConsumer<E> {
    public final void handle(E event) {
        if (alreadyProcessed(event)) return;   // Skip if duplicate
        process(event);                         // Do the work
    }
    protected abstract boolean alreadyProcessed(E event);
    protected abstract void process(E event);
}
```

This ensures events can be safely replayed without creating duplicate data.

---

## 5. The Persistence Layer — How Data Gets Stored

Open `pv-persistence/src/main/java/com/power/posval/persistence/`.

### Entities — JPA Mappings

Each domain model has a corresponding JPA entity:

| Domain Model | JPA Entity | Database Table |
|---|---|---|
| `PositionLedgerEntry` | `PositionLedgerEntryEntity` | `position_ledger.position_ledger_entry` |
| `VolumeSeries` | `VolumeSeriesEntity` | `volume_series.volume_series` |
| `VolumeInterval` | `VolumeIntervalEntity` | `volume_series.volume_interval` |
| `SettlementCell` | `SettlementCellEntity` | `settlement.settlement_cell` |
| `StruckMark` | `StruckMarkEntity` | `settlement.struck_mark` |

### Adapters — The Bridge

Each adapter translates between domain models and JPA entities:

```java
public class JpaSettlementCellRepository implements SettlementCellRepository {

    @Override
    public void save(SettlementCell cell) {
        emProvider.get().persist(toEntity(cell));     // domain → entity
    }

    @Override
    public List<SettlementCell> findByPosition(...) {
        return em.createQuery(...)
            .getResultStream()
            .map(this::toDomain)                      // entity → domain
            .toList();
    }

    private SettlementCellEntity toEntity(SettlementCell c) { /* ... */ }
    private SettlementCell toDomain(SettlementCellEntity e) { /* ... */ }
}
```

**Important detail — JSONB encoding:** Fields like `activeLeaves` (a `Set<String>`) and `inputVersionSet` (a `Map<String, Long>`) are stored as JSON strings in PostgreSQL JSONB columns. The `SimpleJsonCodec` utility class handles the round-trip:

```java
// Saving: Set<String> → JSON array
e.setActiveLeaves(SimpleJsonCodec.setToJson(c.activeLeaves()));
// e.g., Set.of("EPEX_DA15", "SPREAD_5") → ["EPEX_DA15","SPREAD_5"]

// Loading: JSON array → Set<String>
SimpleJsonCodec.jsonToStringSet(e.getActiveLeaves())
// e.g., ["EPEX_DA15","SPREAD_5"] → Set.of("EPEX_DA15", "SPREAD_5")
```

### Bitemporal Audit

The `BitemporalAuditListener` (a JPA entity listener) enforces:
- On `@PrePersist`: sets `knownFrom` to now
- On `@PreUpdate`: only allows changing `knownTo` (you can't edit history — you can only close it)

---

## 6. The Cache Layer — Redis

Open `pv-redis/src/main/java/com/power/posval/redis/`.

Two things live in Redis:

1. **Volume Cache** (`RedisVolumeCache`) — caches netted volume lookups. Key format: `vol:{tenant}:{seriesKey}:{startIso}`. TTL: 24 hours. Invalidated when a `VolumeSuperseded` event arrives.

2. **Forward Mark Store** (`RedisForwardMarkStore`) — stores current forward marks. These are ephemeral (no history) — each new calculation overwrites the previous value. That's intentional: forward marks show "what's the value RIGHT NOW", not "what was it yesterday."

---

## 7. The Kafka Layer — Event Infrastructure

Open `pv-kafka/src/main/java/com/power/posval/kafka/`.

### Four Consumers

| Consumer | Listens for | What it triggers |
|---|---|---|
| `TradeCapturedConsumer` | `PositionCaptured` | Settlement materialization for each position |
| `VolumeSupersededConsumer` | `VolumeSuperseded` | Cache invalidation + revaluation cascade |
| `SettlementPublishedConsumer` | `SettlementComputed` | Rollup refresh |
| `CurveTickConsumer` | Market data updates | Forward mark recalculation |

### The Outbox Relay

`OutboxRelayProducer` is the bridge between the database outbox table and Kafka. It polls for unpublished events and sends them. This ensures exactly-once delivery semantics (database transaction + Kafka acknowledgment).

---

## 8. Dependency Injection — How It's All Wired Together

Open `pv-guice/src/main/java/com/power/posval/guice/`.

Google Guice connects interfaces to implementations. The app has 8 modules:

| Module | What it binds |
|---|---|
| `DomainModule` | Business services: PriceEvaluator, handlers, resolvers |
| `PersistenceModule` | Repository adapters, EntityManager, DataSource |
| `CacheModule` | VolumeCache → RedisVolumeCache, Redis connection |
| `KafkaModule` | Consumers, producers, Kafka client config |
| `EventModule` | DomainEventPublisher, ForwardMarkStore |
| `StubServiceModule` | MarketData, PriceExpression, MeteredActual stubs |
| `TenantModule` | Tenant context and AOP interceptor |
| `ObservabilityModule` | Metrics and health checks |

### Application Bootstrap

**File:** `PositionValuationApp.java`

```java
public static Injector createInjector() {
    return Guice.createInjector(
        new PersistenceModule(),
        new DomainModule(),
        new StubServiceModule(),     // swap this for real services later
        new TenantModule(),
        new EventModule(),
        new CacheModule(),
        new KafkaModule(),
        new ObservabilityModule()
    );
}
```

When you need to swap the JSON stubs for real services, you just replace `StubServiceModule` with a module that binds real implementations.

---

## 9. Following a Complete Code Path — Trade to Settlement

Let's trace a single trade from arrival to settlement cell. This is the path you'd follow in a debugger.

### Scenario
- Trade T-9999, tenant TN_0042
- Fixed price: 85 EUR/MWh
- Delivery: March 2025
- Volume: 50 MW constant profile

### Step-by-Step Code Path

**1. Trade arrives as a command**

```
pv-domain/command/TradeCapture.java
  ↓ passed to
pv-domain/service/DefaultTradeCaptureHandler.handle(TradeCapture cmd)
```

The handler decomposes March 2025 into one monthly block and creates a `PositionLedgerEntry`:
- `tradeId = "T-9999"`
- `priceExpressionId = UUID("00000000-...-000000000001")` (points to fixed 85.00)
- `volumeSeriesKey = SeriesKey("VS-TEST-001")`
- `deliveryRange = DeliveryRange.ofMonth(YearMonth.of(2025, 3), ZoneId.of("Europe/Berlin"))`

It saves the entry via `PositionLedgerRepository.save()` and publishes `PositionCaptured`.

**2. Event flows through the outbox**

```
pv-domain/port/event/DomainEventPublisher.publish(PositionCaptured event)
  ↓ implemented by
pv-kafka (via pv-guice EventModule)
  ↓ OutboxDomainEventPublisher writes to outbox table
  ↓ OutboxRelayProducer polls and sends to Kafka
  ↓ arrives at
pv-kafka/TradeCapturedConsumer.process(PositionCaptured event)
```

**3. Consumer triggers materialization**

```
pv-kafka/TradeCapturedConsumer.process()
  ↓ loads positions from
pv-domain/port/repository/PositionLedgerRepository.findCurrentByTradeLeg()
  ↓ for each position entry, calls
pv-domain/service/SettlementMaterializationJob.execute(position, range)
```

**4. Settlement job runs**

```
AbstractMaterializationJob.execute()     ← template method (FINAL, can't override)
  │
  ├── SettlementMaterializationJob.resolveVolume()
  │     └── ProfileResolver.resolve(volumeRef, range, SETTLEMENT)
  │           └── VolumeSeriesRepository.findCurrentBySeriesKey("VS-TEST-001")
  │                 └── Returns VolumeSeries with 2,976 intervals (96 per day x 31 days)
  │           └── For each interval: volume x multiplier (1.0) → VolumeRecord
  │
  ├── FOR EACH VolumeRecord (each 15-min slot):
  │     │
  │     ├── SettlementMaterializationJob.evaluatePrice()
  │     │     ├── PriceExpressionRepository.findById(UUID "...0001")
  │     │     │     └── Returns ConstantLeaf("FIXED_85", 85.00, "EUR/MWh")
  │     │     └── DefaultPriceEvaluator.evaluate(ConstantLeaf, interval, SETTLEMENT, marketData)
  │     │           └── switch (expr) { case ConstantLeaf c -> c.value() }
  │     │           └── Returns PriceResolution(85.00, {"FIXED_85"}, {})
  │     │
  │     └── SettlementMaterializationJob.writeResult()
  │           ├── amount = 85.00 x 12.5 MWh = 1062.50 EUR
  │           ├── Create SettlementCell(price=85.00, energy=12.5, amount=1062.50)
  │           ├── SettlementCellRepository.save(cell)
  │           └── DomainEventPublisher.publish(SettlementComputed)
```

**5. Result**

For each 15-minute slot in March 2025, a `SettlementCell` record is saved to the database with:
- price = 85.00 EUR/MWh
- volume = 50.0 MW
- energy = 12.5 MWh (50 MW x 0.25 hours)
- amount = 1,062.50 EUR
- activeLeaves = {"FIXED_85"}
- inputVersionSet = {} (no market data used — it's a fixed price)

---

## 10. Following a More Complex Path — Index + Spread Pricing

Now let's trace a trade with market-linked pricing.

### Scenario
- Price formula: EPEX day-ahead 15-min price + 5.00 EUR/MWh spread
- Expression ID: `00000000-...-000000000002`
- Market price at 2025-03-01T00:00:00Z = 72.55 EUR/MWh (settlement series)

### What the price expression tree looks like

```
Add
├── left:  MarketDataLeaf("EPEX_DA15", series="EPEX_DA15",
│                          settlementSeries="EPEX_DA15_SETTLE", ...)
└── right: ConstantLeaf("SPREAD_5", value=5.00, unit="EUR/MWh")
```

### Price evaluation trace

```
DefaultPriceEvaluator.evaluate(Add, interval, SETTLEMENT, marketData)
  │
  ├── eval(Add.left) = eval(MarketDataLeaf)
  │     │ purpose == SETTLEMENT && settlementSeries != null
  │     │ → use "EPEX_DA15_SETTLE" instead of "EPEX_DA15"
  │     └── marketData.lookupFixing("EPEX_DA15_SETTLE", 2025-03-01T00:00:00Z)
  │           └── Returns MarketDataLookup(value=72.55, versionId=1)
  │     └── activeLeaves.add("EPEX_DA15")
  │     └── versions.put("EPEX_DA15_SETTLE", 1)
  │     └── yield 72.55
  │
  ├── eval(Add.right) = eval(ConstantLeaf)
  │     └── activeLeaves.add("SPREAD_5")
  │     └── yield 5.00
  │
  └── result = 72.55 + 5.00 = 77.55

Return PriceResolution(77.55, {"EPEX_DA15", "SPREAD_5"}, {"EPEX_DA15_SETTLE": 1})
```

**Key behavior — purpose-driven series selection (FR-048e):**

When `purpose == SETTLEMENT`, the evaluator uses `settlementSeries` if available. When `purpose == FORWARD`, it uses the primary `series`. This lets the same expression tree produce different results depending on whether you're doing actual settlement or forward projection.

---

## 11. Key Architectural Concepts to Remember

### Sealed Interfaces (Java 21)

The codebase uses `sealed interface` in three places:

1. **`PriceExpression`** — 13 subtypes. `DefaultPriceEvaluator` uses pattern matching to handle each one. The compiler guarantees you handle every type.

2. **`VolumeResolver`** — 2 subtypes (`ProfileResolver`, `ForecastResolver`). Sealed because the volume resolution strategy must be one of exactly these two.

3. **`MaterializationStrategy`** — 3 subtypes for controlling when intervals are materialized.

### Records (Java 16+)

Most value objects are `record` types — immutable, with auto-generated `equals`, `hashCode`, `toString`. Examples: `SettlementCell`, `StruckMark`, `VolumeRecord`, `PriceResolution`, `Money`, `SeriesKey`, `DeliveryPeriod`.

### The Builder Pattern

Complex objects like `PositionLedgerEntry` and `DefaultVolumeSeries` use the Builder pattern:

```java
var position = PositionLedgerEntry.builder()
    .id(UUID.randomUUID())
    .tenantId("TN_0042")
    .tradeId("T-7788")
    .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
    .priceExpressionId(exprId)
    .build();   // validates and creates immutable object
```

### Multitenancy

Every query is scoped by `tenantId`. The `TenantModule` in Guice sets up a `TenantContext` (ThreadLocal) and a `TenantInterceptor` (AOP) that enforces tenant isolation on annotated methods.

### Numeric Precision

All numbers flow through `NumericPrecision` which defines scale per domain:

| Domain | Scale | Example |
|---|---|---|
| MONETARY | 4 | EUR amounts |
| PRICE | 8 | EUR/MWh |
| VOLUME | 8 | MW |
| ENERGY | 8 | MWh |
| INTERMEDIATE | 10 | Scratch math between steps |

This ensures consistent rounding across the entire system.

---

## 12. How to Run the Tests

```bash
# Compile everything
mvn clean compile

# Run all tests
mvn clean test

# Run tests for a specific module
mvn test -pl pv-domain

# Run a specific test class
mvn test -pl pv-domain -Dtest=SettlementMaterializationJobTest
```

### Key Test Files to Study

| Test | What it verifies |
|---|---|
| `AbstractMaterializationJobTest` | Template method calls hooks in correct order |
| `SettlementMaterializationJobTest` | End-to-end: volume + price + settlement with stubs |
| `DefaultPriceEvaluatorTest` | All 13 expression types evaluate correctly |
| `JsonPriceExpressionRepositoryTest` | JSON stub loads expressions (constant, index+spread, collar) |
| `JsonMarketDataPortTest` | JSON stub returns correct fixings, curves, FX rates |
| `JsonMeteredActualRepositoryTest` | JSON stub loads metered data |
| `SimpleJsonCodecTest` | JSONB round-trip for Set and Map types |
| `QualityStateTest` | State machine transitions (EFFECTIVE→AMENDED, etc.) |
| `PositionLedgerEntryTest` | Builder validation and bitemporal invariants |

---

## 13. How to Add a New Feature — Practical Examples

### Example A: "Add a new price expression node type"

1. Create the record in `pv-domain/model/expression/`:
   ```java
   public record MyNewNode(PriceExpression child, BigDecimal factor)
       implements PriceExpression {}
   ```

2. Add it to the `permits` clause in `PriceExpression.java`:
   ```java
   public sealed interface PriceExpression permits
       ConstantLeaf, MarketDataLeaf, ..., MyNewNode { }
   ```

3. The compiler will now **force you** to handle it in `DefaultPriceEvaluator`:
   ```java
   case MyNewNode n -> {
       BigDecimal val = eval(n.child(), ...);
       yield val.multiply(n.factor());
   }
   ```

4. Add it to `JsonPriceExpressionRepository.parseExpression()` if you want JSON support.

5. Write a test in `DefaultPriceEvaluatorTest`.

### Example B: "Store a new field on SettlementCell"

1. Add the field to the record in `pv-domain/model/SettlementCell.java`
2. Add the column to `pv-persistence/entity/SettlementCellEntity.java`
3. Update `JpaSettlementCellRepository.toEntity()` and `toDomain()` to map the field
4. Update `SettlementMaterializationJob.writeResult()` to populate it
5. Write a test

### Example C: "Swap the JSON market data stub for a real service"

1. Create a new module (e.g., `pv-marketdata`) with a class that implements `MarketDataPort`
2. Create a new Guice module (e.g., `RealMarketDataModule`) that binds `MarketDataPort` to your implementation
3. In `PositionValuationApp.createInjector()`, replace `StubServiceModule` (or remove just the MarketData binding) with your new module
4. Done — everything else stays the same because the domain only talks to the `MarketDataPort` interface

---

## 14. Glossary of Key Types

Quick reference for when you're reading code and hit an unfamiliar type:

| Type | Package | What it is |
|---|---|---|
| `PositionLedgerEntry` | `domain.model` | One trade x one delivery month |
| `VolumeSeries` | `domain.model` | Collection of 15-min volume intervals |
| `VolumeInterval` | `domain.model` | One 15-min slot with MW and MWh |
| `VolumeRecord` | `domain.service` | Resolved volume (after multiplier applied) |
| `VolumeReference` | `domain.model.value` | Links a trade to its volume source |
| `SettlementCell` | `domain.model` | Final result: price x volume = money |
| `StruckMark` | `domain.model` | EOD frozen snapshot |
| `ForwardMark` | `domain.port` | Current projected value (ephemeral) |
| `PriceExpression` | `domain.model.expression` | Price formula tree node |
| `PriceResolution` | `domain.service` | Result of evaluating a price formula |
| `PriceEvaluator` | `domain.service` | Walks the price tree and computes value |
| `VolumeResolver` | `domain.service` | Loads and resolves volume data |
| `DeliveryPeriod` | `domain.model.value` | Half-open [start, end) time window |
| `DeliveryRange` | `domain.model.value` | Month-block delivery range |
| `SeriesKey` | `domain.model.value` | Stable external key for a volume series |
| `Money` | `domain.model.value` | BigDecimal + Currency |
| `NumericPrecision` | `domain.port` | Scale/rounding config per domain |
| `ResolutionPurpose` | `domain.service` | FORWARD or SETTLEMENT |
| `QualityState` | `domain.model` | Data quality (EFFECTIVE, CURRENT, VALIDATED...) |
| `SeriesType` | `domain.model` | FORECAST (shared) or PROFILE (per-trade) |
| `TimeGranularity` | `domain.model` | MIN_5, MIN_15, HOURLY, etc. |
| `VolumeUnit` | `domain.model` | MW_CAPACITY or MWH_PER_PERIOD |
| `DomainEventPublisher` | `domain.port.event` | Publishes events to outbox |
| `MarketDataPort` | `domain.port.marketdata` | Looks up market prices |
| `MarketDataLookup` | `domain.port.marketdata` | Single market data observation |
| `DependencyEdge` | `domain.port.repository` | "Cell X depends on input Y" |

---

## 15. Where to Go Next

- **README.md** (in this folder) — non-technical overview of the business domain
- **functional-spec-position-valuation-v1.0.md** — the binding specification (FR-001 through FR-120)
- **CONTEXT-position-valuation-design.md** — why each design decision was made
- **The tests** — the best way to learn is to run the tests in debug mode and step through
