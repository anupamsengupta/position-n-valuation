# Developer Usage Walkthrough — Position & Valuation System

**Audience:** Entry-level developers joining the team. This document walks you through the entire codebase — how it's organized, how data flows from trade capture to final valuation, and how to follow the code when debugging or adding features.

**How to read this:** Start from Section 1 and read linearly. Each section builds on the previous one. Code paths are given as `module/package/ClassName.method()` so you can open the exact file.

---

## 0. Why Are Things Named This Way?

Before diving into code, here's why you'll encounter some unfamiliar names.

### "Port" (as in `MarketDataPort`, `MetricsPort`)

This comes from **Hexagonal Architecture** (also called "Ports & Adapters"), a widely-used pattern in domain-driven design. Think of a physical port on a computer — a USB port defines the shape of the connection but doesn't care what's plugged into it. In our code, `MarketDataPort` says "I need something that can look up market prices" without caring whether the implementation reads from Bloomberg, a database, or a JSON file on disk. The thing you plug into the port is called an **adapter** (e.g., `JsonMarketDataPort` is one adapter, a future `BloombergMarketDataPort` would be another). This naming is standard in Java DDD codebases and avoids ambiguity with the word "interface" (which in Java means something more specific).

### "Leaf" (as in `ConstantLeaf`, `MarketDataLeaf`, `IndexLeaf`)

This comes from **tree data structures** in computer science. Our price formulas form a tree:

```
        Add                          ← internal node (has children)
       /   \
  EPEX_DA15  3.20                    ← leaf nodes (no children — actual data sources)
```

Internal nodes are operators (Add, Multiply, Clamp) that combine their children. **Leaf nodes** are the endpoints where actual values come from — constants, market data lookups, or index references. The term "leaf" is standard in any expression tree, compiler, or calculator implementation.

### "Clamp" (as in the `Clamp` expression type)

This comes from **mathematics and graphics programming**. `clamp(value, min, max)` means "constrain a value to stay within a range":
- If value < min, return min
- If value > max, return max
- Otherwise return value unchanged

In energy trading, this operation is called a **collar** — the buyer pays the market price, but never less than the floor and never more than the cap. We use the word "clamp" in code because it precisely describes the mathematical operation (`max(floor, min(cap, value))`), while "collar" is the business term for the same thing. If you hear a trader say "this PPA has a 42/95 collar", they mean `Clamp(min=42, max=95, inner=marketPrice)`.

### "Sealed" (as in `sealed interface PriceExpression`)

This is a Java 21 language feature. A `sealed interface` declares exactly which classes can implement it. This matters because the compiler can then check that every `switch` statement handles all possible types — you can't forget one. If someone adds a new expression node type, every evaluator that switches over `PriceExpression` will get a compile error until they add a case for it.

### "Escalate" (as in the `Escalate` expression type)

In energy contracts, **price escalation** means adjusting a base price for inflation over time. A PPA signed in 2023 at 72 EUR/MWh might say "escalate annually by German CPI." If CPI rose from 108.70 (at signing) to 112.30 (current), the escalated price is 72 x (112.30 / 108.70) = 74.38 EUR/MWh. In code: `Escalate(base=72.00, ratio=HICP_current / HICP_base)`. The `base` is the original price; the `ratio` is a fraction (usually > 1.0 in inflationary periods) computed from an index.

### "Gate" (as in `ConditionalGate`)

In power trading, a **negative-price gate** is a contract clause that says "if the day-ahead price drops below zero, we pay nothing instead of the normal formula." This is common in wind PPAs because negative prices mean there's more supply than demand — the wind park should curtail rather than pay the grid to take its power. In code: `ConditionalGate(gateInput=EPEX, condition="< 0", overrideValue=0, inner=normalFormula)`. The gate "blocks" the normal formula when the condition is met.

### "Resolution" and "Purpose" (as in `PriceResolution`, `ResolutionPurpose`)

**Resolution** means "the act of computing a concrete value from a formula." A `PriceResolution` is the result: the final EUR/MWh number, plus metadata about which inputs were used.

**Purpose** distinguishes two contexts for the same formula. `SETTLEMENT` means "use actual historical prices to compute real money owed." `FORWARD` means "use forecast/curve prices to estimate future value." The same expression tree can produce different results depending on purpose — a `MarketDataLeaf` with `settlementSeries="EPEX_DA15_SETTLE"` will look up the ex-post clearing price for settlement but the forward curve for forward valuation.

### "Bitemporal" (as in `validFrom/To`, `knownFrom/To`)

Every important record has two time dimensions:
- **Valid time** (`validFrom/To`) — when was this true in the real world? "This trade was effective from March 1."
- **Knowledge time** (`knownFrom/To`) — when did the system learn about it? "We recorded this at 10:05 AM on Feb 28."

This lets you answer audit questions like "what did we think we knew last Tuesday?" without losing history. When data is corrected, the old record gets its `knownTo` set (closing the knowledge window) and a new record is created with `knownFrom` = now.

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

## 2. Project Layout — The 8 Modules

Open the root folder. You'll see 8 Maven modules:

```
position-and-valuation/
├── pv-domain/              ← The brain. All business logic lives here.
├── pv-persistence/         ← Database adapters (PostgreSQL via JPA/Hibernate)
├── pv-redis/               ← Cache adapters (Redis via Lettuce)
├── pv-kafka/               ← Event consumers and producers (Kafka)
├── pv-guice/               ← Dependency injection wiring (Google Guice)
├── pv-integration-tests/   ← End-to-end tests with real H2 database
├── pv-app/                 ← Runnable Spring Boot app with REST API
└── pom.xml                 ← Parent POM tying it all together
```

### The Golden Rule: Dependencies Flow Inward

```
pv-guice  ──→  pv-kafka  ──→  pv-domain  ←──  pv-persistence
                                   ↑
                               pv-redis

pv-app  ──→  pv-kafka + pv-persistence + pv-domain  (Spring Boot wiring)
pv-integration-tests  ──→  pv-kafka + pv-persistence + pv-domain  (manual wiring)
```

- `pv-domain` depends on **nothing** (except `jakarta.inject` annotations). It has zero knowledge of databases, Redis, or Kafka.
- `pv-persistence`, `pv-redis`, `pv-kafka` all depend on `pv-domain` — they implement the interfaces (ports) that the domain defines.
- `pv-guice` depends on everything — it wires the interfaces to their implementations using Google Guice.
- `pv-app` wires the same classes using **Spring Boot** instead of Guice, adds REST controllers, and runs against PostgreSQL + Kafka.
- `pv-integration-tests` wires everything **manually** (no framework) against an H2 in-memory database for fast, debuggable end-to-end tests.

**Why this matters:** When you're debugging business logic, you only need to look inside `pv-domain`. When you're debugging "why isn't this getting saved to the database", look at `pv-persistence`. When you're debugging "why didn't this event trigger", look at `pv-kafka`. When you want to run the full system with HTTP endpoints, use `pv-app`. When you want to step through the entire pipeline with breakpoints, run `pv-integration-tests`.

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

Prices aren't just numbers — they're formulas represented as trees. The stub data in `stub/price-expressions.json` contains 5 progressively complex real-world examples:

**EXPR-1: Fixed bilateral** — the simplest case, a single constant:
```java
new ConstantLeaf("FIXED_85", 85.00, "EUR/MWh")
// Result: always 85.00 regardless of market conditions
```

**EXPR-2: Day-ahead index + premium** — common short-term supply agreement:
```java
new Add(
    new MarketDataLeaf("EPEX_DA15", "EPEX_DA15", "EPEX_DA15_SETTLE", ...),
    new ConstantLeaf("PREMIUM_3_20", 3.20, "EUR/MWh")
)
// Result: EPEX clearing price + 3.20 supplier margin
```

**EXPR-3: Collar PPA** — floor/cap protection on market-linked price:
```java
new Clamp(
    new ConstantLeaf("FLOOR_42", 42.00, "EUR/MWh"),     // floor
    new ConstantLeaf("CAP_95", 95.00, "EUR/MWh"),        // cap
    new MarketDataLeaf("EPEX_DA15_COLLAR", "EPEX_DA15", ...)  // market price
)
// Result: max(42, min(95, market_price))
// If EPEX = 30 → pay 42 (floor kicks in)
// If EPEX = 70 → pay 70 (inside collar)
// If EPEX = 120 → pay 95 (cap kicks in)
```

**EXPR-4: Three-level CPI-escalated collar with negative-price protection** — a real onshore wind PPA:
```
Level 1: ConditionalGate — "if EPEX goes negative, pay zero"
  │
  └── Level 2: Clamp(floor=38, cap=110) — collar protection
        │
        └── Level 3: Escalate — base price adjusted by CPI inflation
              │
              ├── base: 72.00 EUR/MWh (2023 base price)
              └── ratio: HICP_DE_current / 108.70 (CPI ratio since signing)
```
This formula means: start with 72 EUR/MWh, adjust for inflation (if CPI rose from 108.70 to 112.30, price becomes 72 x 112.30/108.70 = 74.38), clamp between 38-110, and zero out if the market goes negative.

**EXPR-5: Cross-border FX PPA** — Norwegian hydro sold to a German buyer:
```
FxConvert
├── value: Subtract(NORDPOOL_SYS, 12.00 NOK discount)
└── fxRate: EUR/NOK daily rate
```
Result: (Nordic system price in NOK - 12 NOK discount) x EUR/NOK conversion rate.

The `PriceExpression` is a **sealed interface** — Java 21 ensures the compiler checks you've handled every type. The 13 node types are:

| Type | What it does | Example |
|---|---|---|
| `ConstantLeaf` | Fixed number | 85.00 EUR/MWh |
| `MarketDataLeaf` | Look up a market price | EPEX day-ahead 15-min |
| `IndexLeaf` | Look up an inflation index | HICP-DE |
| `Add` / `Subtract` | Arithmetic | base + spread |
| `Multiply` / `Divide` | Arithmetic | price x quantity |
| `Clamp` | Floor/cap (collar) | max(floor, min(cap, inner)) |
| `Escalate` | Index escalation | base x (CPI_current / CPI_base) |
| `ConditionalGate` | If/else | if price < 0 then 0 else price |
| `ConditionalPassThrough` | Pass value through | if condition then gateValue else inner |
| `TimeAverage` | Average over window | monthly average price |
| `FxConvert` | Currency conversion | NOK price x EUR/NOK rate |

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
| `MarketDataPort` | Look up market prices | `CachingMarketDataPort` (production), `JsonMarketDataPort` (stub) |
| `MarketDataCache` | Cache market data lookups | `RedisMarketDataCache` |
| `MarketDataRepository` | Persist/load market data | `JpaMarketDataRepository` |
| `VolumeCache` | Cache volume lookups | `RedisVolumeCache` |
| `ForwardMarkStore` | Store ephemeral marks | `RedisForwardMarkStore` |
| `DomainEventPublisher` | Publish events | `OutboxDomainEventPublisher` |
| `DependencyIndex` | Track what depends on what | `JpaDependencyIndex` |
| `RollupRepository` | Aggregated views | `JpaRollupRepository` |

**Production vs Stub implementations:** Market data has a full production stack: `CachingMarketDataPort` (domain service) checks `RedisMarketDataCache` first, falls back to `JpaMarketDataRepository` (Postgres), and populates the cache on miss. Cache invalidation is event-driven via `MarketDataUpdatedConsumer` (Kafka). The production stack is wired by `MarketDataModule`; the JSON stub is wired by `StubServiceModule` for unit tests. Two other external services still have JSON-file-backed stubs in `service/stub/`. All stubs load realistic test data from `src/main/resources/stub/*.json`:

- **`stub/market-data.json`** (~1,250 lines) — 3 full days of EPEX DA15 fixings (288 intervals at 15-min granularity with realistic intraday price curves: ~25 EUR/MWh at night, ~80 EUR/MWh at peak), NORDPOOL system prices in NOK, EEX baseload forward curves spanning 24 months (2-year horizon), HICP-DE inflation index, and daily EUR/USD and EUR/NOK FX rates.

- **`stub/price-expressions.json`** — 5 formulas ranging from simple fixed-price to a 3-level CPI-escalated collar PPA with negative-price protection (see Section 3.1 above).

- **`stub/metered-actuals.json`** (~14,000 lines) — Two renewable assets: an offshore wind park (MTR-WP-NORDSEE, 80 MW capacity, 672 intervals = 7 days) and a solar farm (MTR-SP-BAYERN, 45 MW capacity, 672 intervals). Includes version-2 corrections where the TSO resent validated meter readings days later with small calibration adjustments. Wind corrections cover day 1 (96 intervals with `supersedesId` linking to originals); solar corrections cover days 1-2.

These stubs will be swapped for real service implementations later via Guice module replacement.

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

        // Step 2: Collect results in memory (no I/O yet)
        List<R> results = new ArrayList<>(volumes.size());
        for (VolumeRecord vol : volumes) {
            PriceResolution price = evaluatePrice(position.priceExpressionId(), interval);
            results.add(buildResult(position, vol, price));
        }

        // Step 3: Batch flush — persist + publish all at once
        flushResults(position, results);
    }

    // Subclasses fill in these four "hooks":
    protected abstract List<VolumeRecord> resolveVolume(...);
    protected abstract PriceResolution evaluatePrice(...);
    protected abstract R buildResult(...);      // pure computation, no I/O
    protected abstract void flushResults(...);  // batch persist + batch publish
}
```

Three concrete implementations:

| Job | Type `R` | What it writes | Persistence |
|---|---|---|---|
| `SettlementMaterializationJob` | `SettlementCell` | Settlement cells + events | Durable, bitemporal, batch via `saveAll` + `publishAll` |
| `ForwardMarkJob` | `MarkEntry` (inner record) | Forward marks | Ephemeral (Redis only, overwritten) |
| `EodStrikeJob` | `StruckMark` | Struck marks | Durable, immutable, batch via `saveAll` |

**Following the code for settlement:**

1. `SettlementMaterializationJob.resolveVolume()` — calls `volumeResolver.resolve()` with purpose `SETTLEMENT`
2. `SettlementMaterializationJob.evaluatePrice()` — loads the price expression from `priceExpressionRepo`, then calls `priceEvaluator.evaluate()` with purpose `SETTLEMENT`
3. `SettlementMaterializationJob.buildResult()` — calculates `amount = price x energy`, creates a `SettlementCell` (pure computation, no database call)
4. `SettlementMaterializationJob.flushResults()` — batch-saves all cells via `cellRepo.saveAll()`, builds `SettlementComputed` events from the cells, and batch-publishes via `eventPublisher.publishAll()`

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
  ┌──────────────────────────────────────────────────┐
  │  TradeCapture command arrives                    │
  │  → DefaultTradeCaptureHandler.handle()           │
  │  → Creates PositionLedgerEntry records           │
  │  → Publishes "PositionCaptured" event            │
  │  → Event saved to Outbox table (same transaction)│
  └───────────────────────┬──────────────────────────┘
                          │
STEP 2: Event Relay       │
  ┌───────────────────────▼───────────────────────────┐
  │  OutboxRelayProducer polls outbox table           │
  │  → Sends event to Kafka topic                     │
  │  → Marks outbox row as published                  │
  └───────────────────────┬───────────────────────────┘
                          │
STEP 3: Consumer          │
  ┌───────────────────────▼──────────────────────────┐
  │  TradeCapturedConsumer receives event            │
  │  → Checks idempotency (already processed?)       │
  │  → Loads position entries from ledger            │
  │  → Calls SettlementMaterializationJob.execute()  │
  └───────────────────────┬──────────────────────────┘
                          │
STEP 4: Materialization   │
  ┌───────────────────────▼──────────────────────────┐
  │  SettlementMaterializationJob.execute()          │
  │  For each 15-minute slot:                        │
  │    1. Resolve volume (ProfileResolver)           │
  │    2. Load price expression (repository)         │
  │    3. Evaluate price (tree walk)                 │
  │    4. Calculate: amount = price x energy         │
  │    5. Save SettlementCell                        │
  │    6. Publish "SettlementComputed" event         │
  └───────────────────────┬──────────────────────────┘
                          │
STEP 5: Downstream        │
  ┌───────────────────────▼─────────────────────────┐
  │  SettlementPublishedConsumer                    │
  │  → Triggers rollup refresh (aggregated views)   │
  │  → Updates dependency index                     │
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

Three things live in Redis:

1. **Volume Cache** (`RedisVolumeCache`) — caches netted volume lookups. Key format: `vol:{tenant}:{seriesKey}:{startIso}`. TTL: 24 hours. Invalidated when a `VolumeSuperseded` event arrives.

2. **Market Data Cache** (`RedisMarketDataCache`) — caches market data lookups (fixings, curves, FX, indices, vol surfaces, spreads). Key format: `md:{TYPE}:{tenant}:{series}:{lookupKey}`. TTL varies: 24 hours for stable data (fixings, FX, indices, spreads), 1 hour for volatile data (forward curves, vol surfaces). Invalidated when a `MarketDataUpdated` event arrives via Kafka. This is the production cache behind `CachingMarketDataPort` — on a cache miss, the port loads from Postgres via `JpaMarketDataRepository` and populates the cache before returning.

3. **Forward Mark Store** (`RedisForwardMarkStore`) — stores current forward marks. These are ephemeral (no history) — each new calculation overwrites the previous value. That's intentional: forward marks show "what's the value RIGHT NOW", not "what was it yesterday."

---

## 7. The Kafka Layer — Event Infrastructure

Open `pv-kafka/src/main/java/com/power/posval/kafka/`.

### Five Consumers

| Consumer | Listens for | What it triggers |
|---|---|---|
| `TradeCapturedConsumer` | `PositionCaptured` | Settlement materialization for each position |
| `VolumeSupersededConsumer` | `VolumeSuperseded` | Cache invalidation + revaluation cascade |
| `SettlementPublishedConsumer` | `SettlementComputed` | Rollup refresh |
| `CurveTickConsumer` | `CurveTick` | Forward mark recalculation via dependency index |
| `MarketDataUpdatedConsumer` | `MarketDataUpdated` | Market data Redis cache invalidation |

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
| `StubServiceModule` | MarketData, PriceExpression, MeteredActual stubs (dev/test) |
| `MarketDataModule` | Production market data: JPA repo, Redis cache, CachingMarketDataPort |
| `TenantModule` | Tenant context and AOP interceptor |
| `ObservabilityModule` | Metrics and health checks |

### Application Bootstrap

**File:** `PositionValuationApp.java`

```java
public static Injector createInjector() {
    return Guice.createInjector(
        new PersistenceModule(),
        new DomainModule(),
        new MarketDataModule(),      // production: Postgres + Redis + CachingMarketDataPort
        new StubServiceModule(),     // remaining stubs: PriceExpression, MeteredActual
        new TenantModule(),
        new EventModule(),
        new CacheModule(),
        new KafkaModule(),
        new ObservabilityModule()
    );
}
```

`MarketDataModule` wires the production market data stack (Postgres persistence via `JpaMarketDataRepository`, Redis cache via `RedisMarketDataCache`, and `CachingMarketDataPort` as the `MarketDataPort` implementation). `StubServiceModule` still provides JSON stubs for `PriceExpressionRepository` and `MeteredActualRepository` — these will be swapped for real service modules later. For unit tests, `StubServiceModule` alone provides `JsonMarketDataPort` instead.

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
  │     └── SettlementMaterializationJob.buildResult()
  │           ├── amount = 85.00 x 12.5 MWh = 1062.50 EUR
  │           └── Returns SettlementCell(price=85.00, energy=12.5, amount=1062.50)
  │
  └── SettlementMaterializationJob.flushResults(position, allCells)
        ├── SettlementCellRepository.saveAll(cells)  ← batch persist via BatchWriter
        └── DomainEventPublisher.publishAll(events)  ← batch publish
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

3. **`MaterializationStrategy`** — `EagerStrategy` for PROFILE series interval generation during trade capture. FORECAST and METERED_ACTUAL intervals arrive via import, not system-generated materialization.

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

| Test | Module | What it verifies |
|---|---|---|
| `TradeToSettlementIntegrationTest` | `pv-kafka` | **Full pipeline** — traces all 5 steps from Section 9 end-to-end |
| `AbstractMaterializationJobTest` | `pv-domain` | Template method calls hooks in correct order |
| `SettlementMaterializationJobTest` | `pv-domain` | Materialization with stubs: volume + price + settlement cell |
| `DefaultPriceEvaluatorTest` | `pv-domain` | All 13 expression types evaluate correctly |
| `DefaultTradeCaptureHandlerTest` | `pv-domain` | Trade capture → monthly decomposition + event publish |
| `JsonPriceExpressionRepositoryTest` | `pv-domain` | JSON stub loads all 5 expressions (constant, index+spread, collar, 3-level CPI PPA, FX) |
| `JsonMarketDataPortTest` | `pv-domain` | JSON stub returns fixings, forward curves, FX rates, NORDPOOL |
| `JsonMeteredActualRepositoryTest` | `pv-domain` | JSON stub loads wind + solar metered data with corrections |
| `SimpleJsonCodecTest` | `pv-persistence` | JSONB round-trip for Set and Map types |
| `QualityStateTest` | `pv-domain` | State machine transitions (EFFECTIVE→AMENDED, etc.) |
| `PositionLedgerEntryTest` | `pv-domain` | Builder validation and bitemporal invariants |

### The Integration Test — Following Section 9 in Code

The most important test to study is `TradeToSettlementIntegrationTest` in `pv-kafka/src/test/java/`. It traces the exact code path described in Section 9, through every layer, using in-memory stubs instead of real databases or Kafka.

**File:** `pv-kafka/src/test/java/com/power/posval/kafka/TradeToSettlementIntegrationTest.java`

Here's what the `fullPipeline_fixedPrice_tradeToSettlement` test does, step by step:

```
SETUP — Wire all components manually (same as Guice would do):
  ├── InMemoryLedgerRepo         (replaces JpaPositionLedgerRepository + PostgreSQL)
  ├── InMemoryCellRepo           (replaces JpaSettlementCellRepository + PostgreSQL)
  ├── stubSeriesRepo             (replaces JpaVolumeSeriesRepository + PostgreSQL)
  ├── JsonMarketDataPort         (loads stub/market-data.json from classpath)
  ├── JsonPriceExpressionRepository (loads stub/price-expressions.json)
  ├── DefaultPriceEvaluator      (real domain service — no stub needed)
  ├── ProfileResolver            (real domain service — reads from stubSeriesRepo)
  ├── SettlementMaterializationJob (real domain service — wired with all of the above)
  ├── DefaultTradeCaptureHandler (real domain service — writes to InMemoryLedgerRepo)
  └── TradeCapturedConsumer      (real Kafka consumer — calls settlementJob)

TEST EXECUTION:

  Step 1: TradeCapture command
  │  TradeCapture("T-9999", fixedPrice=85.00, 50MW, March 2025)
  │  → tradeCaptureHandler.handle(command)
  │  → Creates 1 PositionLedgerEntry (single-month trade)
  │  → Saves to InMemoryLedgerRepo
  │  → Publishes PositionCaptured event
  │  ✓ Assert: 1 entry in ledger, status=ACTIVE, tenantId=TN_0042
  │  ✓ Assert: 1 PositionCaptured event with tradeId=T-9999
  │
  Step 2: (outbox relay simulated — event passed directly)
  │
  Step 3: Consumer receives event
  │  tradeCapturedConsumer.handle(capturedEvent)
  │  → alreadyProcessed() → false (first time)
  │  → process() → loads entries from InMemoryLedgerRepo
  │  → for each entry: calls settlementJob.execute(entry, range)
  │
  Step 4: Materialization runs
  │  AbstractMaterializationJob.execute() [template method]:
  │    ├── resolveVolume() → ProfileResolver reads from stubSeriesRepo
  │    │   → Returns VolumeRecord(50 MW, 12.5 MWh) for 00:00-00:15
  │    ├── evaluatePrice() → loads EXPR-1 from JsonPriceExpressionRepository
  │    │   → ConstantLeaf(85.00)
  │    │   → DefaultPriceEvaluator: switch(ConstantLeaf) → 85.00
  │    │   → PriceResolution(85.00, {"FIXED_85"}, {})
  │    ├── buildResult() → amount = 85.00 × 12.5 = 1062.50
  │    │   → Returns SettlementCell (collected, not yet persisted)
  │    └── flushResults() → batch saves all cells, batch publishes events
  │
  Step 5: Verify outputs
  │  ✓ Assert: 1 SettlementCell with price=85.00, energy=12.5, amount>0
  │  ✓ Assert: positionId links back to the ledger entry from Step 1
  │  ✓ Assert: activeLeaves contains "FIXED_85"
  │  ✓ Assert: 1 SettlementComputed event with status="PROVISIONAL"
```

The test has 4 methods covering different scenarios:

| Test method | What it proves |
|---|---|
| `fullPipeline_fixedPrice_tradeToSettlement` | All 5 steps work end-to-end with a simple 85 EUR/MWh fixed price |
| `fullPipeline_indexPlusSpread_tradeToSettlement` | Same pipeline with EPEX market price + 3.20 premium — proves market data lookup, purpose-driven series selection, and active-leaves tracking across multiple leaf nodes |
| `idempotency_duplicateEventDoesNotCreateDuplicateCells` | Same event processed twice → second time skipped by `alreadyProcessed()` guard → no duplicate settlement cells |
| `multiMonthTrade_createsOneEntryPerMonth` | A 3-month trade (Mar–May) → `DeliveryPeriod.toMonthBlocks()` → 3 ledger entries |

**To run just this test:**

```bash
mvn clean test -pl pv-kafka -am -Dtest=TradeToSettlementIntegrationTest
```

The `-am` flag ("also make") tells Maven to build `pv-domain` and `pv-persistence` first, since `pv-kafka` depends on them.

**To debug in your IDE:** Set a breakpoint at `tradeCaptureHandler.handle(command)` and step through. You'll see the flow traverse `DefaultTradeCaptureHandler` → `PositionLedgerEntry.builder()` → `DomainEventPublisher.publish()` → `TradeCapturedConsumer.process()` → `SettlementMaterializationJob.execute()` → `ProfileResolver.resolve()` → `DefaultPriceEvaluator.evaluate()` → `SettlementCellRepository.save()`. Every layer from Section 9 is hit.

### Running JMH Benchmarks

The project includes JMH (Java Microbenchmark Harness) benchmarks for measuring the performance of core domain operations. Unlike unit tests, JMH benchmarks use proper warmup, measurement iterations, and forked JVMs to produce statistically reliable numbers.

#### Available Benchmarks

| Benchmark Class | What it measures | Approximate runtime |
|---|---|---|
| `FiveYearSettlementBenchmark` | End-to-end settlement for a 5-year wind PPA (~175k intervals, 60 monthly positions, 3-level CPI-escalated collar expression) | ~2 minutes |
| `PriceExpressionBenchmark` | Single collar PPA price expression tree walk | ~30 seconds |
| `DomainBenchmarkSuite` | Domain model micro-benchmarks (QualityState transitions, DeliveryPeriod decomposition, builder patterns, numeric precision) | ~3 minutes |

#### How to Run

```bash
# Step 1: Compile (including JMH annotation processing)
mvn -pl pv-domain test-compile

# Step 2: Run ALL benchmarks (takes ~6 minutes)
mvn -pl pv-domain exec:exec@benchmarks

# Run a SPECIFIC benchmark by name filter:
mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=FiveYear
mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=PriceExpression
mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=DomainBenchmark

# Run a single benchmark method:
mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=materializeSingleMonth
```

#### Reading the Output

JMH produces output like this:

```
Benchmark                                                      Mode   Cnt    Score   Error  Units
FiveYearSettlementBenchmark.materializeFiveYearTrade          sample    5  245.123 ± 12.456  ms/op
FiveYearSettlementBenchmark.materializeFiveYearTrade:p0.50    sample         243.200         ms/op
FiveYearSettlementBenchmark.materializeFiveYearTrade:p0.95    sample         258.100         ms/op
FiveYearSettlementBenchmark.materializeFiveYearTrade:p0.99    sample         261.400         ms/op
```

- **Score** = average time per operation (lower is better)
- **Error** = margin of error (± range for the 99.9% confidence interval)
- **p0.50 / p0.95 / p0.99** = percentile latencies (median, 95th, 99th)
- **Units** = `ms/op` means milliseconds per operation, `us/op` means microseconds

#### Where Results Are Saved

JSON results are written to `pv-domain/target/jmh-results.json` after each run. You can feed this file into JMH visualization tools or track performance over time.

#### Smoke Tests vs Full Benchmarks

The `BenchmarkSmokeTest` class runs each benchmark **once** as a regular JUnit test — no warmup, no forking, no statistics. This just verifies that benchmark code compiles and executes correctly:

```bash
mvn test -pl pv-domain -Dtest=BenchmarkSmokeTest
```

Use smoke tests for quick CI verification. Use the full JMH runner (via `exec:exec@benchmarks`) for actual performance measurement.

#### How It Works Under the Hood

1. The `jmh-generator-annprocess` annotation processor runs during `test-compile` and generates `/META-INF/BenchmarkList` — a registry of all `@Benchmark` methods.
2. `BenchmarkRunner.main()` reads the filter argument and passes it to JMH's `OptionsBuilder`.
3. JMH forks a separate JVM for each benchmark class (controlled by `@Fork`), runs warmup iterations (controlled by `@Warmup`), then measurement iterations (controlled by `@Measurement`).
4. The `@Setup(Level.Trial)` method pre-loads all data into memory before any measurement begins, so you're benchmarking pure computation — not I/O.

### End-to-End Integration Tests with Real Database (`pv-integration-tests`)

The `TradeToSettlementIntegrationTest` above uses **in-memory stubs** (HashMaps instead of databases). That's great for speed and isolation, but it doesn't exercise real SQL, real batch writing, or real cache-through patterns.

The `pv-integration-tests` module goes further — it wires all **real JPA adapters** against an **H2 in-memory database**, runs real SQL, and verifies the full pipeline with actual persistence.

**Key files:**

```
pv-integration-tests/
  src/test/
    resources/META-INF/persistence.xml       # H2 config with 5 schemas + jsonb domain
    java/com/power/posval/integration/
      EndToEndValuationIT.java               # Main test (6 phases)
      support/
        IntegrationTestWiring.java           # Manual DI — no Guice, no Spring
        VolumeSeriesGenerator.java           # Synthetic wind/solar volume data
        MarketDataDbLoader.java              # stub/market-data.json → H2 via JPA
```

**Why manual wiring?** Rather than starting Guice or Spring Boot, `IntegrationTestWiring` constructs every component by hand — `new JpaMarketDataRepository(emProvider)`, `new BatchWriter(emProvider)`, etc. This means zero framework overhead, ~2 second startup, and you can set a breakpoint at any constructor to watch the entire object graph assemble.

**How the EntityManager is shared:** A `ThreadLocal<EntityManager>` lambda provider ensures all components in a call chain get the same EM:

```java
Provider<EntityManager> emProvider = () -> {
    EntityManager em = emThreadLocal.get();
    if (em == null || !em.isOpen()) {
        em = emf.createEntityManager();
        emThreadLocal.set(em);
    }
    return em;
};
```

**The 6-phase test pipeline:**

```
Phase 1: Load Market Data
  stub/market-data.json → MarketDataDbLoader → JpaMarketDataRepository
  → H2: INSERT INTO market_data.fixing ... (x ~8,800 fixings)

Phase 2: Create Volume Series (2 assets)
  VolumeSeriesGenerator → wind (2,976 intervals) + solar (1,488 intervals)
  → H2: INSERT INTO volume_series.volume_interval ... (batch writer)

Phase 3: Capture 6 Trades (3 per asset, multipliers 0.3 + 0.3 + 0.4 = 1.0)
  TradeCapture commands → DefaultTradeCaptureHandler → 12 ledger entries per trade
  → H2: INSERT INTO position.position_ledger_entry ...

Phase 4: Settlement Materialization
  TradeCapturedConsumer → SettlementMaterializationJob
  → CachingMarketDataPort (cache miss → H2 query → cache hit on repeat)
  → PriceEvaluator evaluates EXPR-4 (CPI-escalated collar → ~74.38 EUR/MWh)
  → H2: INSERT INTO valuation.settlement_cell ... (x 13,392 cells)

Phase 5: Verify Database State
  Direct JPQL queries confirm all 13,392 cells persisted with correct prices

Phase 6: Verify Cache Behavior
  cache.cacheHits = 23,807 (repeated lookups)
  cache.cacheMisses = 2,977 (first lookup per key)
```

**To run:**

```bash
mvn clean test -pl pv-integration-tests -am
```

**To debug:** Set a breakpoint at `CachingMarketDataPort.lookupFixing()` to watch cache misses hit H2 and populate the cache. Set a breakpoint at `SettlementMaterializationJob.buildResult()` to inspect the CPI-escalated price calculation.

**Performance:** The full 6-phase pipeline runs in ~21 seconds single-threaded against in-process H2.

> **Tech spec reference:** `docs/technical-spec/integration-testing-tech-spec.md`

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
4. Update `SettlementMaterializationJob.buildResult()` to populate it
5. Write a test

### Example C: "The JSON market data stub has already been swapped for a production service"

This has already been done. The production stack is:
1. `CachingMarketDataPort` (in `pv-domain/service/`) implements `MarketDataPort` — checks Redis cache first, falls back to Postgres, populates cache on miss
2. `JpaMarketDataRepository` (in `pv-persistence/adapter/`) — 6 JPA entity tables in `market_data` schema (fixings, forward curves, FX rates, indices, vol surfaces, spreads)
3. `RedisMarketDataCache` (in `pv-redis/`) — tenant-isolated Redis cache with type-differentiated TTLs
4. `MarketDataUpdatedConsumer` (in `pv-kafka/`) — Kafka consumer invalidates cache on data changes
5. `MarketDataModule` (in `pv-guice/`) — wires it all together
4. Done — everything else stays the same because the domain only talks to the `MarketDataPort` interface

---

## 14. Volume Series Import — Getting Data Into the System

Sections 3–9 show how trades flow through the system. But where does the **volume data** come from? PROFILE series (per-trade volume shapes) are created automatically during trade capture. But FORECAST series (shared wind/solar predictions) and METERED_ACTUAL series (real meter readings from the TSO) arrive from outside the system via **import**.

### The Import Pipeline

The import system follows a three-phase pipeline: **parse → validate → persist**.

```
External Source                    Domain Port                     Database
─────────────────                 ──────────                      ────────
CSV files (2-file)  ─→ CsvImportParser ─┐
Excel workbook      ─→ ExcelImportParser─┤─→ DefaultVolumeSeriesImporter ─→ JpaVolumeSeriesRepository
Programmatic API    ─────────────────────┘     (validate + persist)            (BatchWriter)
                                                      │
                                                      ▼
                                               DomainEventPublisher
                                          (VolumePublished / VolumeSuperseded)
```

### Three Ways to Import

**1. CSV (two-file approach):**

Two CSV files linked by `series_key`:
- `series_metadata.csv` — one row per series (key, type, asset, granularity, delivery window)
- `intervals.csv` — many rows per series (timestamp, volume, optional energy)

```csv
# series_metadata.csv
series_key,series_type,asset_id,volume_unit,time_granularity,delivery_start,delivery_end,delivery_timezone
FCST-WP-NORDSEE,FORECAST,WP-NORDSEE,MW_CAPACITY,MIN_15,2025-01-01T00:00:00+01:00,2030-01-01T00:00:00+01:00,Europe/Berlin

# intervals.csv
series_key,interval_start,interval_end,volume,energy
FCST-WP-NORDSEE,2025-01-01T00:00:00Z,2025-01-01T00:15:00Z,48.500,
FCST-WP-NORDSEE,2025-01-01T00:15:00Z,2025-01-01T00:30:00Z,47.200,
```

When `energy` is left blank, the system computes it: `energy = volume × (duration_hours)`. For 48.5 MW over 15 minutes: `48.5 × 0.25 = 12.125 MWh`.

**2. Excel (.xlsx workbook):**

Same data, two sheets: Sheet 1 = "SeriesMetadata", Sheet 2 = "Intervals". Parsed by `ExcelImportParser` using Apache POI.

**3. Programmatic API:**

For system-to-system integration (weather forecast feeds, metering APIs):

```java
var request = new SeriesImportRequest(
    "FCST-WP-NORDSEE", SeriesType.FORECAST, "WP-NORDSEE", null,
    VolumeUnit.MW_CAPACITY, TimeGranularity.MIN_15,
    deliveryPeriod, null, null, null, intervals);
ImportResult result = importer.importSeries(List.of(request), "TN_0042");
```

### Auto-Supersession — What Happens When Data Is Updated

When you import a series with a `series_key` that already exists:

1. The system finds the existing series via `findCurrentBySeriesKey()`
2. If the existing series has the **same type** (e.g., both FORECAST) → **supersede**: mark old as SUPERSEDED, create new with incremented version
3. If the existing series has a **different type** → **reject** with error (you can't change a FORECAST into a PROFILE)
4. If the `(key, version)` already exists → **skip** (idempotent, already imported)

Supersession triggers a `VolumeSuperseded` event, which cascades downstream: settlement cells are revalued, caches are invalidated, and forward marks are recalculated.

### Validation Rules

The importer validates aggressively before persisting anything:

| Check | What it catches |
|-------|----------------|
| `series_key` non-blank, ≤ 128 chars | Malformed identifiers |
| FORECAST requires `asset_id`; PROFILE requires `trade_leg_id` | Ownership violations (D-11) |
| `delivery_end > delivery_start` | Backwards time ranges |
| All intervals within `[delivery_start, delivery_end)` | Out-of-range data |
| No overlapping intervals | Duplicate time slots |
| Interval duration matches `time_granularity` | Mismatched granularity (warning) |

Each series in a batch is an independent unit-of-work. If series A fails validation, series B and C are still imported.

### Performance

A 5-year PPA at 15-minute granularity = ~175,200 intervals. Target: imported in under 30 seconds per series. The `BatchWriter` flushes/clears every 50 rows (configurable via `pv.batch.size`) to keep memory bounded.

> **Tech spec reference:** `docs/technical-spec/TECH-SPEC-volume-series-import-v1.0.md`

---

## 15. Running the Spring Boot App (`pv-app`)

Everything described in Sections 1–13 runs as library code wired by Guice (production) or manual DI (tests). The `pv-app` module packages the same code into a **runnable Spring Boot application** with REST endpoints, so you can interact with the system via HTTP.

### Quick Start

```bash
# Start PostgreSQL + Kafka (Docker required)
cd pv-app
docker compose up -d

# Build and run
cd ..
mvn clean install -pl pv-app -am -DskipTests
mvn -pl pv-app spring-boot:run
```

The app starts on port 8080. On startup it seeds ~9,000 market data entries from `stub/market-data.json` into PostgreSQL.

### How Spring Boot Wires the Same Classes

The domain services use `jakarta.inject.@Inject` — which works in both Guice and Spring. The tricky part is `Provider<EntityManager>`: every JPA adapter calls `emProvider.get()` to obtain an EntityManager. Spring Boot's normal `@PersistenceContext` injection wouldn't work here.

The solution is a `ThreadLocal`-based provider called `SpringEntityManagerProvider`:

```
REST request → TenantFilter (set tenant from X-Tenant-Id header)
  → Controller → TransactionalExecutor.execute()
    → SpringEntityManagerProvider.bind(em)  ← all components now share this EM
    → begin TX
      → TradeCaptureHandler.handle()
        → PositionLedgerRepository.save()           [same EM via emProvider.get()]
        → OutboxDomainEventPublisher.publish()       [same EM, same TX]
    → commit TX
    → unbind + close EM
```

This ensures the trade save and the outbox event write happen in the **same database transaction** — exactly like the Guice production wiring.

### REST API Overview

**Trade lifecycle** — the main write operations:

| Endpoint | What it does |
|----------|-------------|
| `POST /api/trades/capture` | Capture a new trade → creates position ledger entries |
| `POST /api/trades/amend` | Amend an existing trade → supersedes old entries |
| `POST /api/trades/cancel` | Cancel a trade → forward unwind or void |

**Queries** — read from the database:

| Endpoint | What it returns |
|----------|----------------|
| `GET /api/positions?tenantId=...&tradeId=...&tradeLegId=...` | Current position ledger entries |
| `GET /api/positions/as-of?...&businessDate=...&knowledgeDate=...` | Positions as they were known at a point in time |
| `GET /api/positions/by-range?...&deliveryStart=...&deliveryEnd=...` | All positions in a delivery window |
| `GET /api/settlements?tenantId=...&positionId=...&rangeStart=...&rangeEnd=...` | Settlement cells (price × volume = money) |
| `GET /api/volume-series?tenantId=...` | All volume series for a tenant |
| `GET /api/market-data/fixings?tenantId=...&series=...&intervalStart=...` | A single market data fixing |

**Operations:**

| Endpoint | What it returns |
|----------|----------------|
| `GET /api/health` | Database connectivity status |
| `GET /api/cache/stats` | Cache sizes, hit/miss counters |

### The Async Settlement Pipeline

When you capture a trade via `POST /api/trades/capture`, settlement doesn't happen immediately. Instead:

```
You:     POST /api/trades/capture        → 200 OK (ledger entries)
         ↓ (same transaction writes to outbox)
System:  OutboxRelayScheduler (every 500ms)
         → reads outbox → sends to Kafka topic "posval.PositionCaptured"
         ↓
System:  TradeCapturedKafkaListener (daemon thread)
         → receives event → runs SettlementMaterializationJob
         → volume × price → settlement cells saved to PostgreSQL
         ↓
You:     GET /api/settlements?...         → settlement cells with prices
```

The ~2 second delay between capture and settlement is the outbox relay interval + Kafka delivery.

### What's Different from Production Guice Wiring

| Concern | `pv-guice` (production) | `pv-app` (Spring Boot) |
|---------|------------------------|----------------------|
| DI framework | Google Guice | Spring Boot |
| Database | Aurora PostgreSQL | Local PostgreSQL (Docker) |
| Cache | Redis (`pv-redis`) | In-memory (`ConcurrentHashMap`) |
| Tenant isolation | RLS via `SET LOCAL app.tenant_id` | Header-based only (no RLS) |
| Schema management | Flyway migrations | `hibernate.hbm2ddl.auto=update` |
| Auth | Production auth | None (open endpoints) |

The **domain services are identical** — same `DefaultTradeCaptureHandler`, same `SettlementMaterializationJob`, same `DefaultPriceEvaluator`. Only the infrastructure wiring differs.

### Smoke Testing the Full Flow

```bash
# 1. Health check
curl http://localhost:8080/api/health

# 2. Check seeded market data
curl "http://localhost:8080/api/market-data/fixings?tenantId=default&series=EPEX_DA15&intervalStart=2025-03-01T00:00:00Z"

# 3. Capture a trade (see tech spec for full JSON payload)
curl -X POST http://localhost:8080/api/trades/capture \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{ ... }'

# 4. Wait ~2 seconds, then query settlements
curl "http://localhost:8080/api/settlements?tenantId=default&positionId=<uuid>&rangeStart=...&rangeEnd=..."
```

> **Tech spec reference:** `docs/technical-spec/TECH-SPEC-spring-boot-service-module-v1.0.md`

---

## 16. Glossary of Key Types

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
| `MarketDataPort` | `domain.port.marketdata` | Looks up market prices (fixings, curves, FX, indices, vol surfaces, spreads) |
| `MarketDataLookup` | `domain.port.marketdata` | Single market data observation |
| `VolSurfaceLookup` | `domain.port.marketdata` | Single volatility surface observation |
| `MarketDataType` | `domain.port.marketdata` | Enum: FIXING, FORWARD_CURVE, FX_RATE, INDEX, VOL_SURFACE, SPREAD |
| `MarketDataCache` | `domain.port.cache` | Cache port for market data (Redis-backed) |
| `MarketDataRepository` | `domain.port.repository` | Persistence port for market data (Postgres-backed) |
| `CachingMarketDataPort` | `domain.service` | Production MarketDataPort: cache → DB → populate |
| `MarketDataUpdated` | `domain.event` | Cache invalidation event for market data changes |
| `CurveTick` | `domain.event` | Forward curve update event triggering S5b recalc |
| `DependencyEdge` | `domain.port.repository` | "Cell X depends on input Y" |
| `VolumeSeriesImporter` | `domain.port.ingest` | Port for CSV/Excel/programmatic volume import |
| `SeriesImportRequest` | `domain.port.ingest` | One series to import (header + intervals) |
| `ImportResult` | `domain.port.ingest` | Import outcome: counts + errors |
| `SpringEntityManagerProvider` | `app.provider` | ThreadLocal `Provider<EntityManager>` for Spring Boot |
| `TransactionalExecutor` | `app.provider` | EM bind → begin TX → work → commit → unbind → close |
| `ApiResponse` | `app.dto` | `{ data, message, timestamp }` REST response wrapper |

---

## 17. Where to Go Next

- **README.md** (in this folder) — non-technical overview of the business domain
- **functional-spec-position-valuation-v1.0.md** — the binding specification (FR-001 through FR-120)
- **CONTEXT-position-valuation-design.md** — why each design decision was made
- **integration-testing-tech-spec.md** — detailed spec for the H2-backed integration test module
- **TECH-SPEC-volume-series-import-v1.0.md** — CSV/Excel/API import pipeline specification
- **TECH-SPEC-spring-boot-service-module-v1.0.md** — Spring Boot app architecture, REST API, async settlement
- **The tests** — the best way to learn is to run the tests in debug mode and step through
- **The Spring Boot app** — `cd pv-app && docker compose up -d`, then `mvn -pl pv-app spring-boot:run` for a live system
