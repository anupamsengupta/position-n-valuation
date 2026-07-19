# Position & Valuation System — Plain Language Guide

**Purpose:** This document explains what our system does in everyday language, so anyone on the team (product, QA, ops, management) can understand and validate the design without reading the 1000-line technical specs.

**What this covers:**
1. What problem we're solving
2. How the system is structured (the big picture)
3. Volume Series — how we track electricity quantities
4. Position & Valuation — how we track money and risk
5. How the two halves talk to each other
6. Key rules the system must never break

---

## 1. What Problem Are We Solving?

We're building a system for **electricity trading companies** in Europe. These companies buy and sell power — sometimes years in advance, sometimes minutes before delivery. They need to know:

- **"What did we promise to deliver/receive?"** (the trade contracts)
- **"What do we expect to actually flow?"** (forecasts, for wind/solar)
- **"What actually happened?"** (meter readings after the fact)
- **"How much money are we making or losing?"** (valuation)
- **"What was our risk at any point in the past?"** (regulatory audits)

The tricky part: European electricity is traded in 15-minute slots. A single year-long contract creates ~35,000 individual time slots. Multiply that by hundreds of contracts across 200 customer companies, and you have billions of data points to manage correctly.

---

## 2. The Big Picture

Think of the system as two halves joined by a handshake:

```
                    THE SYSTEM
    ┌─────────────────────┬─────────────────────────┐
    │   VOLUME SERIES     │   POSITION & VALUATION  │
    │   (The Quantities)  │   (The Money)           │
    │                     │                         │
    │   "How much power?" │   "How much money?"     │
    │                     │                         │
    │   3 independent     │   Trade ledger          │
    │   data streams:     │   + Price formulas      │
    │                     │   + Market prices       │
    │   - Contractual     │   + Calculated values   │
    │   - Forecast        │                         │
    │   - Metered actual  │                         │
    └─────────────────────┴─────────────────────────┘
                    │
            Connected by a
          "consumption contract"
         (the handshake rules)
```

---

## 3. Volume Series — Tracking Electricity Quantities

### 3.1 The Three Streams (Layers)

The system tracks electricity volumes in **three completely independent streams**. Think of them as three separate filing cabinets that never mix:

| Stream | What it answers | Who fills it | Owned by | When it changes |
|---|---|---|---|---|
| **CONTRACTUAL** | "What did we agree to trade?" | Trade capture (when a deal is booked) | **Per trade** | Almost never (only if the contract is amended) |
| **FORECAST** | "What do we expect to generate/receive?" | Weather models, asset managers | **Per asset** (shared) | Frequently (every time the weather forecast updates) |
| **METERED ACTUAL** | "What actually flowed through the wire?" | The grid operator (TSO) sends meter data | **Per asset** (shared) | After delivery — first a rough number, later a confirmed one |

**Why three separate streams?** Because they have completely different lives:
- Contractual data is locked in stone (it's a legal agreement)
- Forecasts change constantly (weather is unpredictable)
- Meter readings arrive late and get corrected

Mixing them in one place would be like keeping your signed contract, your weather app, and your electricity bill all in the same filing cabinet with no labels.

### 3.1a Assets vs Trades — A Critical Distinction

This is one of the most important concepts in the system:

**An asset (e.g., a wind farm) has ONE physical output.** The weather forecast predicts the whole farm's output. The meter reads the whole farm's output. These exist independently of any trade.

**Multiple trades can slice up one asset's output using multipliers:**

```
WindPark-Nordsee (100 MW rated capacity)
  │
  ├── Trade T-7788: buys 30% of output  (multiplier = 0.30)
  ├── Trade T-8899: buys 30% of output  (multiplier = 0.30)
  └── Trade T-9900: buys 40% of output  (multiplier = 0.40)
                                          ─────────────────
                                          Total: 100% allocated
```

**Real-world analogy:** Think of a pizza parlor. The oven (asset) produces one pizza. Three customers (trades) have standing orders for 30%, 30%, and 40% of every pizza. The oven doesn't bake three separate pizzas — it bakes one, and each customer gets their slice.

**What this means for the system:**
- The **forecast** is stored ONCE for the whole wind farm (e.g., "expect 72.5 MW at 18:45")
- The **meter reading** is stored ONCE for the whole farm (e.g., "measured 68.3 MW at 18:45")
- Each trade's share is calculated at read time: Trade T-7788 gets `72.5 × 0.30 = 21.75 MW`
- If the forecast updates, we store the update ONCE, and all three trades' valuations are recalculated

**Why not store separate copies per trade?** Because:
- 1 forecast update would require 3 writes instead of 1 (and for 50 trades on one asset, 50 writes)
- The original asset-level data would be lost (you can't add up trade shares if multipliers overlap or change)
- Changing a multiplier (e.g., selling 10% of your allocation to another buyer) would require rewriting all volume data

**Not all trades reference an asset.** Exchange fills (DA, intraday), bilateral flat blocks, and monthly forwards are simple fixed-quantity trades. They have no asset, no forecast, no meter. Their volume comes from the contract itself (e.g., "50 MW flat for August").

### 3.2 What's Inside Each Stream

Each stream stores a list of **time slots** with a power value. For example:

```
Contractual Volume for Trade T-7788:
  Aug 1, 2026  00:00-00:15  →  50 MW
  Aug 1, 2026  00:15-00:30  →  50 MW
  Aug 1, 2026  00:30-00:45  →  50 MW
  ... (about 35,000 slots for a full year)
```

### 3.3 The "Store As Uploaded" Rule

**Key principle:** We store data exactly as it arrives. No forced conversion.

- If a contract is written in hourly slots → we store hourly slots
- If a forecast arrives in 15-minute slots → we store 15-minute slots
- Meter data always comes at 15-minute slots (that's how the grid works)

We never automatically crunch hourly data into 15-minute data, or vice versa. If the consumer (the money side) needs a different resolution, **they** do the conversion.

**Why?** Because converting data loses information and introduces rounding errors. The original is the source of truth.

### 3.4 Handling Long Contracts (Rolling Window)

A 10-year solar contract has ~3.5 million time slots. We don't create them all at once. Instead:

1. Create the next 3 months of slots immediately
2. Each month, create the next month's slots
3. Track progress: "we've built 6 of 120 months so far"

Think of it like a road being paved: you don't pave 100 km on day one, you pave the next stretch as you need it.

### 3.5 Optional Compaction (Summary Views)

Sometimes people want to see a year of 15-minute data summarized at monthly level (for a quick overview). We can create a **summary view** on request, but:

- The original detail is never deleted
- The summary is always re-creatable from the original
- It's a convenience view, not the truth

### 3.6 Versioning

Each stream has its own version counter. When data changes:

- A new forecast arrives → forecast version goes from v5 to v6
- Meter data gets corrected → meter version goes from v1 to v2
- Contract amended → contractual version goes from v1 to v2

These version numbers are independent. A new forecast (v6) doesn't change the contractual version (still v1).

### 3.7 Quality States

Each stream tracks data quality:

| Stream | Quality states | Meaning |
|---|---|---|
| Contractual | EFFECTIVE → AMENDED | "This is the active deal" vs "This was superseded by a change" |
| Forecast | CURRENT → SUPERSEDED | "This is the latest forecast" vs "A newer one replaced it" |
| Metered Actual | PROVISIONAL → VALIDATED | "Rough reading (D+1)" vs "Confirmed by the grid operator (D+7 to D+30)" |

---

## 4. Position & Valuation — Tracking the Money

### 4.1 The Position Ledger (What We Owe / Are Owed)

The Position Ledger is the **legal record of our trading obligations**. For each trade:

- Who traded with whom
- How much power (signed: + means we buy, − means we sell)
- For which delivery period
- At what delivery point (which part of the grid)
- In which portfolio/book

**Critical property: Full audit trail (bitemporality)**

The ledger answers not just "what's our position now?" but "what did we THINK our position was last Tuesday at 3pm?" This is required by European regulators (REMIT, MiFID II).

Example: If we correct a trade on Wednesday, we can still reconstruct what we believed on Monday — because the old version is never deleted, only superseded.

### 4.2 Price Expressions (How to Calculate the Price)

Simple trades have a fixed price (e.g., "65 EUR per MWh"). But many deals — especially renewables — have **formula prices**:

```
Example: Wind PPA pricing formula
  1. Start with the day-ahead auction price
  2. Apply an annual inflation adjustment (CPI index)
  3. Set a floor (minimum 42 EUR) and cap (maximum 95 EUR)
  4. BUT: if the day-ahead price is negative, pay nothing (overrides the floor)
```

The system stores the **recipe** (the formula), not pre-calculated prices. Prices are computed per interval when needed, using the actual market data available at that time.

### 4.3 Three Types of Valuation

| Type | What it answers | Stored how | Changes? |
|---|---|---|---|
| **Settlement** (realized) | "We delivered power in slot X. Actual price × actual volume = actual money." | Permanently, with full audit trail | Only if inputs are corrected |
| **Forward mark** (unrealized) | "For future slots, current curve × forecast volume = expected money." | Temporarily — overwritten every time the curve moves | Constantly (it's a live estimate) |
| **End-of-Day struck mark** | "Official daily snapshot of our risk exposure." | Permanently, per month bucket per business day | Never (it's a frozen snapshot) |

**Why this split matters:** Without it, storing live marks for every 15-minute slot for every trade would create billions of rows. By keeping forward marks ephemeral (just the latest number), we avoid a storage explosion.

### 4.4 The Slot Cache (The Grid Display)

The position grid that traders see on screen shows a **net position** per time slot:

```
Portfolio "Wind-DE" on Aug 15, 2026:
  00:00-00:15: Net +12.5 MW  (= buy 50 - sell 37.5)
  00:15-00:30: Net +12.5 MW
  ...
```

This is pre-calculated and cached for fast display. It's always rebuildable from the ledger — it's a convenience, not a source of truth.

---

## 5. How the Two Halves Connect (The Handshake)

The Position & Valuation side **consumes** volume data from the Volume Series side. The rules:

### 5.1 Who Reads What

| Valuation purpose | Which volume stream to read | Multiplier? |
|---|---|---|
| Settlement (what happened) | Read **METERED ACTUAL** (from asset) | Yes — apply trade's multiplier |
| Forward marks (what we expect) | Read **FORECAST** (from asset) | Yes — apply trade's multiplier |
| Grid display (what's the shape) | Read **CONTRACTUAL** (from trade-leg) | No — already trade-scoped |

For asset-linked trades: the system looks up the `AssetVolumeReference` to find the right asset series AND the multiplier, then computes `asset_volume × multiplier` to get the trade's share.

### 5.2 No Mixing

If meter data isn't available yet, the system does NOT substitute the forecast. It just says "no settlement value yet — waiting for meter." Each stream answers a different question, and mixing them would corrupt the numbers.

### 5.3 Events (Notifications)

When volume data changes, the Volume Series side sends a notification:

```
"Hey, METERED ACTUAL for asset WindPark-Nordsee on Aug 15 just changed
 from v1 to v2 (quality: PROVISIONAL → VALIDATED)"
```

The Position & Valuation side then:
1. Looks up ALL trades that reference WindPark-Nordsee (e.g., T-7788, T-8899, T-9900)
2. For each trade, recalculates the affected money cells using that trade's multiplier
3. Doesn't re-do everything — just the specific intervals that changed, for the specific trades affected

**One asset event → fan-out to all trades on that asset.** This is the key behavior that comes from the asset-centric model.

### 5.4 Granularity Conversion

Volume data may be stored at hourly resolution, but valuation needs 15-minute slots. The rule is: **the consumer (valuation side) does the expansion**, not the volume side.

For a 50 MW hourly block expanded to 15-minute:
- Each 15-min slot gets 50 MW (power rate stays the same)
- Each 15-min slot gets 12.5 MWh energy (total energy divided by 4)

---

## 6. Rules the System Must Never Break

### 6.1 Energy Can't Disappear

If you split a 1-hour block into four 15-minute blocks, the total energy must stay the same. A 50 MW hour = 50 MWh. Four 15-minute slots of 50 MW = 4 × 12.5 MWh = 50 MWh. Always.

### 6.2 No Gaps, No Overlaps

Within any volume stream, every time slot must connect perfectly to the next. No missing slots, no double-counted slots. Like tiles on a floor — no gaps, no overlaps.

### 6.3 Layers Don't Interfere

Updating the forecast never changes the contractual data. Receiving meter readings never changes the forecast. Each stream lives in its own world.

### 6.4 Versions Only Go Up

Version numbers always increase. You can't go from v5 back to v3. New data always gets a higher version.

### 6.5 Contractual Data is Immutable

Once a trade is booked, its contractual volumes are locked. They can only change if the trade itself is legally amended (which creates a new version of the entire trade).

### 6.6 Settlement is Never Assumption-Based

A settlement cell (real money) is only created when the actual meter reading AND the actual price are both available. We never calculate "real" money using forecasted volumes — that would be a Forward mark, not a Settlement.

### 6.7 The Audit Question Must Always Be Answerable

"What did we believe our risk was on date X?" must always be answerable from stored data. This is a regulatory requirement (REMIT). We can never lose this history.

---

## 7. Timezone & Daylight Saving (Why It Matters)

European electricity is settled in **local time** (Berlin time for Germany). Twice a year, clocks change:

| Event | Effect on a single day | Impact |
|---|---|---|
| Spring forward (March) | Day has 23 hours | 92 slots instead of 96 |
| Fall back (October) | Day has 25 hours | 100 slots instead of 96 |

If the system ignores this, energy calculations will be wrong by ~4% on those days. Over millions of euros of trading, that's a significant reconciliation break. The system handles this by always computing time differences using the actual clock (not by assuming "1 day = 24 hours").

---

## 8. Storage & Data Lifecycle

### 8.1 Database

We use **Amazon Aurora PostgreSQL 16** — a managed cloud database. Data is split into monthly partitions (all August data in one partition, all September in another) for efficient querying and cleanup.

### 8.2 How Long We Keep Data

| Regulation | Requirement |
|---|---|
| REMIT / MiFID II | Keep for 5-7 years |
| GDPR | Delete after that |

After 7.5 years, monthly partitions are automatically dropped.

### 8.3 Scale

For 200 customers with ~20 long-term contracts each:
- ~320-430 million rows of volume data in the "active" partitions
- This is well within Aurora's capabilities with proper indexing

---

## 9. Data Models — What Gets Stored Where

This section gives the team a mental map of the database tables, their relationships, and what data lives in each. Think of it as the "blueprint of the warehouse" before we build it.

### 9.1 The Full Table Map

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         VOLUME SERIES SIDE                                   │
│                                                                             │
│   PER TRADE-LEG                    PER ASSET (shared across trades)          │
│  ┌─────────────────────────┐  ┌──────────────────────┐  ┌───────────────┐  │
│  │ contractual_volume_series│  │ forecast_volume_series│  │metered_actual_│  │
│  │ (header / metadata)     │  │ (header / metadata)  │  │volume_series  │  │
│  │                         │  │                      │  │(header)       │  │
│  │  - series_key           │  │  - series_key        │  │ - series_key  │  │
│  │  - trade_id / leg_id    │  │  - asset_id ←────────│──│ - asset_id    │  │
│  │  - version_id           │  │  - version_id        │  │ - version_id  │  │
│  │  - granularity          │  │  - granularity       │  │ - granularity │  │
│  │  - delivery_start/end   │  │  - delivery_start/end│  │ - delivery_*  │  │
│  │  - volume_unit (MW/MWh) │  │  - forecast_source_id│  │ - metering_pt │  │
│  │  - quality_state        │  │  - rated_capacity_mw │  │ - rated_cap_mw│  │
│  │  - formula (recipe)     │  │  - quality_state     │  │ - quality_state│ │
│  └───────────┬─────────────┘  └──────────┬───────────┘  └───────┬───────┘  │
│              │ 1:many                     │ 1:many               │ 1:many   │
│              ▼                            ▼                      ▼          │
│  ┌─────────────────────────┐  ┌──────────────────────┐  ┌───────────────┐  │
│  │ contractual_interval    │  │ forecast_interval    │  │metered_actual_│  │
│  │ (the time slots)        │  │ (FULL ASSET output)  │  │interval       │  │
│  │                         │  │                      │  │(FULL ASSET)   │  │
│  │  - interval_start       │  │  - interval_start    │  │ - interval_*  │  │
│  │  - interval_end         │  │  - interval_end      │  │ - volume      │  │
│  │  - volume (MW or MWh)   │  │  - volume            │  │ - energy      │  │
│  │  - energy (MWh derived) │  │  - energy            │  │               │  │
│  │  - status               │  │                      │  │               │  │
│  │  - chunk_month          │  │                      │  │               │  │
│  └─────────────────────────┘  └──────────────────────┘  └───────────────┘  │
│                                                                             │
│  ┌─────────────────────────────────────────────┐                            │
│  │ asset_volume_reference                      │   THE LINK TABLE           │
│  │ (connects trades to assets with multiplier) │                            │
│  │                                             │                            │
│  │  - asset_id          (which wind farm)      │                            │
│  │  - trade_leg_id      (which trade)          │                            │
│  │  - multiplier        (0.30 = 30% share)     │                            │
│  │  - effective_from/to (when this applies)    │                            │
│  │  - forecast_series_key                      │                            │
│  │  - metered_series_key                       │                            │
│  └─────────────────────────────────────────────┘                            │
│                                                                             │
│  ┌─────────────────────────┐                                                │
│  │ compaction_view         │  (Optional summary — created on user request)  │
│  │  - source_series_id     │                                                │
│  │  - target_granularity   │                                                │
│  │  └→ compacted_interval  │                                                │
│  └─────────────────────────┘                                                │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                      POSITION & VALUATION SIDE                               │
│                                                                             │
│  ┌──────────────────────┐   ┌──────────────────────┐                        │
│  │ position_ledger (S1) │   │ price_expression (S2)│                        │
│  │ (the legal record)   │   │ (pricing recipes)    │                        │
│  │                      │   │                      │                        │
│  │  - position_id       │   │  - expression_id     │                        │
│  │  - version           │   │  - version           │                        │
│  │  - tenant_id         │   │  - tree structure    │                        │
│  │  - trade_id / leg_id │   │    (nodes + leaves)  │                        │
│  │  - signed_quantity   │   │  - valid_from/to     │                        │
│  │  - delivery_point    │   │  - known_from/to     │                        │
│  │  - portfolio / book  │   │                      │                        │
│  │  - delivery_range    │   └──────────────────────┘                        │
│  │  - price_expr_ref ──────────────────┘                                    │
│  │  - volume_unit       │                                                   │
│  │  - valid_from/to     │  (bitemporal: "when true" + "when known")         │
│  │  - known_from/to     │                                                   │
│  └──────────┬───────────┘                                                   │
│             │                                                               │
│             │ feeds into (derived)                                           │
│             ▼                                                               │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │settlement_cell (S5a) │  │forward_mark (S5b)    │  │eod_struck_mark   │  │
│  │(real money, durable) │  │(estimates, ephemeral)│  │(S5c, frozen)     │  │
│  │                      │  │                      │  │                  │  │
│  │ - position_id        │  │ - position_id        │  │ - position_id    │  │
│  │ - interval           │  │ - interval           │  │ - delivery_month │  │
│  │ - value (EUR)        │  │ - value (EUR)        │  │ - business_day   │  │
│  │ - status (PROV/FINAL)│  │ - (overwritten!)     │  │ - value          │  │
│  │ - input_version_set  │  │                      │  │ - input_versions │  │
│  │ - active_leaves      │  │                      │  │ - (immutable!)   │  │
│  │ - valid/known times  │  │                      │  │                  │  │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────┘  │
│                                                                             │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │ slot_cache (S6)      │  │ rollup_aggregate (S7)│  │dependency_index  │  │
│  │ (grid display)       │  │ (reporting)          │  │(S8, routing)     │  │
│  │                      │  │                      │  │                  │  │
│  │ - delivery_point     │  │ - level (hr/day/mo)  │  │ - input_series   │  │
│  │ - portfolio          │  │ - period             │  │ - cell_id        │  │
│  │ - interval           │  │ - peak/offpeak       │  │ - active_leaves  │  │
│  │ - net_mw             │  │ - net_mw / net_mwh   │  │                  │  │
│  │ - net_mwh            │  │ - settled_value      │  │ "When input X    │  │
│  │ - version_hash       │  │ - forward_mark_value │  │  changes, which  │  │
│  │                      │  │                      │  │  cells to redo?" │  │
│  │ (rebuildable cache)  │  │ (rebuildable)        │  │ (rebuildable)    │  │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 Volume Series Tables — In Detail

#### The "Header" Tables (one row per series version)

Think of these as the **cover page** of a document — metadata about the whole series:

| Table | What it represents | Owned by | Key fields (plain meaning) |
|---|---|---|---|
| `contractual_volume_series` | "This trade promised X MW from date A to date B" | **Trade-leg** | series_key, trade_id, granularity (slot size), delivery window, volume_unit, formula (the recipe) |
| `forecast_volume_series` | "This wind farm is expected to produce X MW from A to B" | **Asset** | series_key, asset_id, forecast_source (model/vendor), rated_capacity_mw, quality (CURRENT or old) |
| `metered_actual_volume_series` | "This wind farm's meter measured X MW from A to B" | **Asset** | series_key, asset_id, metering_point (physical meter ID), rated_capacity_mw, quality (PROVISIONAL or VALIDATED) |

#### The Link Table: `asset_volume_reference`

This small but crucial table connects trades to assets:

| Field | Example | Meaning |
|---|---|---|
| `asset_id` | WP-NORDSEE | Which wind farm |
| `trade_leg_id` | T-7788-LEG-1 | Which trade |
| `multiplier` | 0.30 | "This trade gets 30% of the farm's output" |
| `effective_from` | 2026-08-01 | When this allocation starts |
| `effective_to` | 2027-08-01 | When this allocation ends |
| `forecast_series_key` | FCST-WP-NORDSEE | Points to the farm's forecast series |
| `metered_series_key` | MTR-WP-NORDSEE | Points to the farm's meter series |

**Multiplier rules:**
- Each individual multiplier is between 0 and 1 (e.g., 0.30 = 30%)
- The sum across all trades for one asset should ideally be 1.0 (100% allocated)
- Sum < 1.0 is fine (uncontracted capacity — the farm hasn't sold its full output yet)
- Sum > 1.0 triggers a warning (over-selling — a risk condition, but not blocked)

**Not all trades have this link.** Exchange fills and flat bilateral deals don't reference an asset — they have no `asset_volume_reference` row.

#### The "Interval" Tables (many rows per series — the actual data)

These are the **bulk of the data**. Each row = one time slot:

| Table | Owned by | Fields | Example row |
|---|---|---|---|
| `contractual_interval` | Trade-leg | series_id, start, end, volume, energy, status, chunk_month | series=abc, 2026-08-01 00:00–00:15, 50.000000 MW, 12.500000 MWh, CONFIRMED |
| `forecast_interval` | **Asset** | series_id, start, end, volume, energy | series=def, 2026-08-01 00:00–00:15, **72.500000 MW** (full farm output), 18.125000 MWh |
| `metered_actual_interval` | **Asset** | series_id, start, end, volume, energy | series=ghi, 2026-08-01 00:00–00:15, **68.300000 MW** (full farm output), 17.075000 MWh |

**Key point:** Forecast and metered intervals store the **full asset output**, not a trade's share. The trade's share is calculated at read time by applying the multiplier:

```
Farm forecast for 18:45 = 72.5 MW (stored once)

Trade T-7788 reads it: 72.5 × 0.30 = 21.75 MW  (calculated, not stored)
Trade T-8899 reads it: 72.5 × 0.30 = 21.75 MW  (calculated, not stored)
Trade T-9900 reads it: 72.5 × 0.40 = 29.00 MW  (calculated, not stored)
```

**Scale per table:** ~2,976 rows per series per month (31 days x 96 slots). But because forecast and meter are per asset (not per trade), the storage is much smaller — one farm with 5 trades stores 2,976 forecast rows, not 14,880.

#### The VolumeFormula (the "Recipe")

Attached to the contractual series. Tells the system HOW to generate intervals:

```
VolumeFormula for Trade T-7788:
  baseVolume:   50 MW        (flat power rate)
  minVolume:    40 MW        (tolerance floor)
  maxVolume:    55 MW        (tolerance ceiling)
  profileType:  BASELOAD     (24/7, no weekend reduction)
  calendarId:   "DE-LU-2026" (for peak/offpeak, holidays)
```

For shaped profiles (e.g., different MW at night vs day), it contains `ShapingEntry` records:

```
ShapingEntry:
  days:    Mon-Fri
  start:   08:00
  end:     20:00
  volume:  75 MW    (daytime)

ShapingEntry:
  days:    Mon-Sun
  start:   20:00
  end:     08:00
  volume:  30 MW    (nighttime)
```

### 9.3 Position & Valuation Tables — In Detail

#### Position Ledger (S1) — The Legal Record

One row per trade-leg per delivery-month per version. This is surprisingly compact:

| Field | Example | Why it's there |
|---|---|---|
| `position_id` | POS-5501 | Unique ID for this month-block |
| `version` | 3 | How many times this block has been revised |
| `tenant_id` | TN_0042 | Customer isolation |
| `trade_id` / `leg_id` | T-7788 / LEG-1 | Links back to the actual trade |
| `signed_quantity` | +50.0 | Positive = we buy; negative = we sell |
| `quantity_ref` | VS-3312 | OR a reference to the volume series (for shaped deals) |
| `delivery_point` | DE_LU (Germany-Luxembourg zone) | Where on the grid |
| `portfolio` / `book` | "Renewables" / "Wind-DE" | Organizational attribution |
| `delivery_range` | [2026-08-01, 2026-09-01) | Which month this row covers |
| `price_expression_ref` | PXE-9001 | Points to the pricing formula |
| `valid_from` / `valid_to` | 2026-08-01 / open | "When was this true in the real world?" |
| `known_from` / `known_to` | 2026-07-15 14:32 / open | "When did the system learn this?" |

**Row count:** A full-year PPA = 12 rows (one per month) × ~2-5 versions = **24-60 rows total**. That's the entire legal record for a year-long contract. Extremely compact.

#### Settlement Cells (S5a) — Real Money, Permanent

One row per delivered time slot per trade. This is where most of the data lives:

| Field | Example | Meaning |
|---|---|---|
| `position_id` | POS-5501 | Which position block |
| `interval_start` | 2026-08-15 18:45 | Which 15-min slot |
| `value_eur` | 777.48 | Computed: price × volume for this slot |
| `status` | FINAL | PROVISIONAL (rough) → FINAL (confirmed) |
| `input_version_set` | {DA:v1, CPI:v1, MET:v2, expr:v1} | Exactly which inputs produced this number |
| `active_leaves` | {DA15, METER} | Which formula inputs actually mattered |
| `known_from` / `known_to` | 2026-08-22 / open | Audit trail: when we computed this |

**Row count:** ~35,000 per full-year PPA × 1.3 (corrections) = **~45,500 rows per contract lifetime**. This is the biggest table.

#### Forward Marks (S5b) — Temporary Estimates

Only current state, no history. Overwritten constantly:

| Field | Example | Meaning |
|---|---|---|
| `position_id` | POS-5508 | Which position |
| `interval_start` | 2027-03-15 10:00 | Which future slot |
| `mark_value_eur` | 892.50 | Current estimated value (curve × forecast) |

**Row count:** Only exists for undelivered slots in the "hot window" (next ~60 days). When a slot is delivered and settles, its forward mark is deleted and a settlement cell replaces it.

#### EOD Struck Marks (S5c) — Official Daily Snapshots

One row per position per delivery-month per business day. Frozen after creation:

| Field | Example | Meaning |
|---|---|---|
| `position_id` | POS-5501 | Which position |
| `delivery_month` | 2026-08 | Aggregated at month level (NOT per slot) |
| `business_day` | 2026-09-15 | Which EOD this was struck |
| `mark_value_eur` | 1,247,000 | Total marked value for this month-bucket |
| `curve_version` | DE_LU_CURVE:v487 | Exact curve used |
| `fx_version` | ECB:v2026-09-15 | Exact FX rate used |

**Row count:** 12 months × ~285 business days = **~3,420 rows per contract lifetime**. Very small.

**Why not store per-interval?** Because 35,040 intervals × 285 days = 10 million rows per contract. Monthly buckets give 3,420 rows with the same audit value (you can always recalculate the per-interval detail from the stored curve version).

#### Slot Cache (S6) — The Grid Display

What traders actually see on screen. Pre-calculated net positions:

| Field | Example | Meaning |
|---|---|---|
| `delivery_point` | DE_LU | Grid zone |
| `portfolio` | "Wind-DE" | Book |
| `interval_start` | 2026-08-15 18:45 | Time slot |
| `net_mw` | +12.5 | Net position in MW (buy - sell) |
| `net_mwh` | +3.125 | Net position in MWh |
| `is_peak` | true | Peak hour? (for filtering) |
| `version_hash` | 0x7F3A... | For staleness detection |

**Row count:** Hot window (60 days × 96 slots) × number of distinct (point, portfolio) combos. Typically low millions per tenant. **Entirely rebuildable** from the ledger.

#### Dependency Index (S8) — The "What to Recalculate" Map

Maps inputs to the valuation cells they affect:

```
When DA price for 2026-08-15 18:45 changes:
  → Recalculate settlement_cell for POS-5501 at 18:45
  → Recalculate settlement_cell for POS-6602 at 18:45
  → (but NOT POS-7703 because DA was inactive for that one — neg-price gate fired)
```

This is what makes targeted recalculation efficient — we never scan the whole valuation store.

### 9.4 How Tables Relate to Each Other

```
Asset (e.g., WindPark-Nordsee)
  │
  ├──→ ForecastVolumeSeries ──→ ForecastIntervals (full asset output)
  │
  ├──→ MeteredActualVolumeSeries ──→ MeteredActualIntervals (full asset output)
  │
  └──→ AssetVolumeReference(s): one per trade that slices this asset
           │
           │   multiplier = 0.30
           ▼
Trade T-7788, Leg 1
  │
  ├──→ ContractualVolumeSeries ──→ ContractualIntervals (this trade's shape)
  │         │
  │         └── VolumeFormula (recipe)
  │
  └──→ Position Ledger rows (1 per month of delivery)
           │
           ├──→ PriceExpression (pricing recipe)
           │
           ├──→ Settlement Cells (real money per delivered slot)
           │         uses: metered × 0.30 for volume
           │
           ├──→ Forward Marks (estimated money per future slot)
           │         uses: forecast × 0.30 for volume
           │
           └──→ EOD Struck Marks (official daily snapshot per month)
```

**Key insight:** The asset's forecast/meter data is stored once and shared. Each trade gets its slice via the multiplier in `AssetVolumeReference`. The volume and position sides are linked by `series_key` references and live in separate tables and services.

### 9.5 What Is a "Source of Truth" vs "Derived/Rebuildable"

| Structure | Source of truth? | If we lose it... |
|---|---|---|
| Position Ledger | YES | We've lost the legal record. Unrecoverable. |
| PriceExpression | YES | We've lost the pricing recipes. Unrecoverable. |
| Volume Series (all 3) | YES | We've lost the volumes. Unrecoverable. |
| Settlement Cells | YES (derived but durable) | Must recompute from ledger + prices + volumes |
| EOD Struck Marks | YES (frozen snapshot) | Cannot be recreated after the business day passes |
| Forward Marks | NO (ephemeral) | Just recalculate from current curves. No loss. |
| Slot Cache | NO (rebuildable) | Rebuild from ledger. Temporary UI outage only. |
| Rollup Aggregates | NO (rebuildable) | Rebuild from cells/cache. Temporary reporting gap. |
| Dependency Index | NO (rebuildable) | Rebuild by re-resolving all cells. Slow but lossless. |

---

## 10. Storage Architecture — How Data Is Physically Organized

### 10.1 Database Platform

| Component | Technology | Why |
|---|---|---|
| Primary database | Amazon Aurora PostgreSQL 16 | Managed, scalable, supports partitioning natively |
| Partition management | pg_partman extension | Auto-creates/drops monthly partitions |
| Scheduled jobs | pg_cron extension | Runs retention cleanup, materialization extension |
| Event streaming | Kafka 3.7 (KRaft mode) | Volume events, trade events, chunk processing |
| Hot cache | Redis 7 | Forward marks, slot cache hot tier |
| Application | Java 21 / Spring Boot 3.3 | Platform standard |

### 10.2 Partitioning Strategy (How Data Is Split)

All large tables are **partitioned by delivery month**. Think of it like filing cabinets organized by month:

```
contractual_interval
  ├── contractual_interval_2026_08  (all Aug 2026 delivery slots)
  ├── contractual_interval_2026_09  (all Sep 2026 delivery slots)
  ├── contractual_interval_2026_10  (all Oct 2026 delivery slots)
  └── ... (one partition per month)
```

**Why monthly?**
- Queries almost always ask "show me August data" — the database only looks in one partition
- Cleanup is easy: drop the whole partition after 7.5 years (instead of deleting rows one by one)
- New months are pre-created 6 months ahead (so we're never caught off guard)

**Tenant isolation:** Every query includes `tenant_id` as the first filter. The database uses this + delivery month to find data instantly without scanning everything.

### 10.3 Index Strategy (How We Find Data Fast)

| Index | On table | Purpose |
|---|---|---|
| `(series_id, interval_start)` | All interval tables | "Give me slots for this series in time order" |
| `(series_key, version_id)` | All series header tables | "Give me a specific version of this series" |
| `(tenant_id, delivery_range)` | Position ledger | "All positions for this customer in this month" |
| `(position_id, interval_start)` | Settlement cells | "All settled values for this position" |
| `(delivery_point, portfolio, interval)` | Slot cache | "Net position for this grid cell" |

### 10.4 Sizing — Real Numbers

For a typical deployment (200 tenants, ~20 PPAs each, referencing ~8 distinct assets per tenant):

| Table | Rows in "active" partitions | Growth rate | Note |
|---|---|---|---|
| Contractual intervals | ~143 million | Grows as new months materialize | Per trade-leg |
| Forecast intervals | ~28 million | Depends on forecast frequency | **Per asset** (shared!) |
| Metered actual intervals | ~28 million | Grows only for delivered months | **Per asset** (shared!) |
| Asset volume references | ~32,000 | Grows with new trades | Tiny link table |
| Position ledger | ~720,000 | Very slow growth | Very compact! |
| Settlement cells | ~2.7 billion (all tiers) | Grows as delivery progresses | Biggest table |
| EOD struck marks | ~14 million | Small, steady growth | |
| Slot cache | ~tens of millions | Fixed size (hot window only) | Rebuildable |
| Rollup aggregates | ~low millions | Fixed size | Rebuildable |

**The key insight:** Because forecast and meter data is per asset (not per trade), the storage is dramatically smaller. If 5 trades reference the same wind farm, forecast data is stored once — not 5 times. The position ledger (the legal record) is tiny — less than 1M rows for the whole fleet. The settlement cells are the largest store but only ~12-14 months sit in "hot" partitions at any time.

### 10.5 Data Flow — The Life of a Number

Here's how a single electricity delivery flows through the system. Note how the asset stores data ONCE and multiple trades consume it:

```
STEP 0: Asset onboarded (before any trades)
  └─→ WindPark-Nordsee registered as asset (100 MW rated capacity)
  └─→ ForecastVolumeSeries created (per asset — stores full farm output)
  └─→ MeteredActualVolumeSeries created (per asset — stores full farm output)

STEP 1: Trade T-7788 is booked — buys 30% of asset (Day 0)
  └─→ Position Ledger: +30 MW for Aug 2026 (1 row)
  └─→ ContractualVolumeSeries: 2,976 intervals created for this trade's Aug
  └─→ AssetVolumeReference: (asset=WP-Nordsee, trade=T-7788, multiplier=0.30)
  └─→ Slot Cache: 2,976 cells updated with forecast × 0.30

STEP 1b: Trade T-8899 is booked — buys 30% of SAME asset
  └─→ Position Ledger: +30 MW for Aug 2026 (1 row)
  └─→ AssetVolumeReference: (asset=WP-Nordsee, trade=T-8899, multiplier=0.30)
  └─→ Same forecast data, different multiplier → different trade volumes

STEP 2: Weather forecast arrives (Day 0 + hours)
  └─→ ForecastVolumeSeries: 2,976 intervals (e.g., 35-95 MW full farm output)
  └─→ STORED ONCE for the asset, NOT per trade
  └─→ Forward Marks for T-7788: 2,976 cells (curve × forecast × 0.30)
  └─→ Forward Marks for T-8899: 2,976 cells (curve × forecast × 0.30)
  └─→ (One forecast write triggers revaluation for ALL trades on the asset)

STEP 3: End of business day
  └─→ EOD Struck Mark: 1 row per trade per month (frozen with curve version)

STEP 4: Power is delivered (Aug 1)
  └─→ MeteredActualVolumeSeries: 96 intervals arrive (D+1) — FULL FARM output
  └─→ Settlement for T-7788: 96 cells (price × meter × 0.30)
  └─→ Settlement for T-8899: 96 cells (price × meter × 0.30)
  └─→ Forward Marks: 96 cells DELETED per trade (no longer future)

STEP 5: Meter data confirmed (Aug 7)
  └─→ MeteredActualVolumeSeries: version v1 → v2 (quality: VALIDATED)
  └─→ Settlement Cells for ALL trades on this asset: recalculated, → FINAL
```

### 10.6 Event-Driven vs Batch Processing

The system uses TWO processing modes for the same calculations:

| Mode | When | What it does | Authority |
|---|---|---|---|
| **Event-driven** | Real-time (seconds after a change) | Recalculates only the specific cells affected by a change | Not authoritative — a "best effort" freshness layer |
| **Batch** | Scheduled (end of day, hourly) | Recalculates everything, heals any drift, produces official marks | **Authoritative** — the system of record |

**Why both?** Events give traders a live, responsive screen. Batch ensures everything is eventually 100% correct. If the event system has a hiccup, the next batch run fixes it. You could ship batch-only first and add events later — the correctness is in the batch.

### 10.7 The "Hot Window" Concept

Not all data is treated equally. The system maintains a temperature model:

```
┌──────────────────────────────────────────────────────────────────────┐
│  HOT (today → T+60 days + current delivery month)                    │
│                                                                      │
│  - Slot cache populated (fast grid display)                          │
│  - Forward marks live (constantly updating)                          │
│  - Event-driven updates active                                       │
│  - Redis-cached for sub-millisecond reads                            │
│  - This is what traders interact with                                │
├──────────────────────────────────────────────────────────────────────┤
│  WARM (delivered months within 7-year retention window)               │
│                                                                      │
│  - Settlement cells durable (audit/regulatory)                       │
│  - No slot cache (grid reads from rollups)                           │
│  - No forward marks (past = settled only)                            │
│  - Queried for audit, recon, disputes                                │
├──────────────────────────────────────────────────────────────────────┤
│  PURGE (> 7 years post-settlement)                                   │
│                                                                      │
│  - Monthly partitions dropped entirely                               │
│  - No data retained (GDPR compliance)                                │
│  - Automatic via pg_cron job                                         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 11. Key Design Patterns for the Tech Spec

These patterns should guide implementation decisions:

### 11.1 "Store the Recipe, Derive the Meal"

| Stored (source of truth) | Derived (rebuildable) |
|---|---|
| VolumeFormula (50 MW baseload, Berlin timezone, 15-min) | 35,040 interval rows |
| PriceExpression (collar formula with CPI + neg-gate) | Per-interval EUR values |
| Position Ledger (+50 MW, Aug block) | Slot cache cells, rollups |
| Market data (DA price 68.20 for slot X) | Settlement cell value 777.48 EUR |

If a derived artifact is corrupted or lost, it can always be rebuilt from the stored recipe + reference data.

### 11.2 "Append, Never Overwrite" (for auditable data)

Position ledger, settlement cells, metered volume versions — new versions are added alongside old ones. The old row gets a `known_to` timestamp (marking when it stopped being current), but is never deleted or modified. This creates the audit trail.

**Exception:** Forward marks ARE overwritten (they're ephemeral by design — no audit value for intraday estimates).

### 11.3 "Tenant as Leading Key Everywhere"

Every query path starts with `WHERE tenant_id = ?`. This is the first filter, the first index column, the isolation guarantee. Cross-tenant queries are architecturally impossible (not just forbidden by policy — the query planner physically can't find other tenants' data efficiently).

### 11.4 "Idempotent Recomputation"

If you process the same event twice, you get the same result. No side effects, no double-counting. This means:
- Events can be safely replayed (Kafka at-least-once delivery)
- Failed batch jobs can be restarted without cleanup
- Duplicate messages are harmless

The key mechanism: recomputation always starts from source data (not from deltas), and the result is keyed by its input versions. Same inputs → same output → no-op on second write.

### 11.5 "Dependency-Indexed Recomputation"

When something changes (e.g., a new meter reading), we don't recalculate everything. The dependency index tells us exactly which valuation cells used that meter reading. We recalculate only those — typically tens or hundreds of cells, not millions.

---

## 12. Glossary of Key Terms

| Term | Plain meaning |
|---|---|
| **Aggregate** | A group of related objects treated as a unit (e.g., a series + its intervals) |
| **Asset** | A physical power generation facility (wind farm, solar park, gas plant) that produces electricity |
| **AssetVolumeReference** | The link between a trade and an asset, carrying the multiplier (trade's share) |
| **Atomic interval** | The smallest time slot (usually 15 minutes in Germany) |
| **Bidding zone** | A region of the electricity grid where one price applies (e.g., Germany-Luxembourg) |
| **Bitemporal** | Tracking two timelines: "when was it true?" AND "when did we know it?" |
| **Cascade** | Breaking a large block (e.g., yearly) into smaller ones (quarterly → monthly) |
| **DST** | Daylight Saving Time — clocks changing twice a year |
| **Ephemeral** | Temporary; overwritten and not kept as history |
| **Fan-out** | Expanding one big interval into many small ones |
| **Forward mark** | An estimated future value based on current market curves |
| **Granularity** | The size of time slots (15-min, hourly, daily, monthly) |
| **Ledger** | The permanent record of all trading positions |
| **Multiplier** | A trade's share of an asset's output (0.30 = 30%); applied at read time, not stored on volume data |
| **MW** | Megawatt — a rate of power (like speed: km/h) |
| **MWh** | Megawatt-hour — an amount of energy (like distance: km) |
| **Netting** | Adding up buys and sells to get the net position |
| **PPA** | Power Purchase Agreement — a long-term renewable energy contract |
| **Roll-up** | Summing fine-grained data into coarser summaries |
| **Settlement** | The final financial reconciliation after power is actually delivered |
| **Supersession** | Replacing old data with new data (but keeping the old for audit) |
| **TSO** | Transmission System Operator — runs the physical grid, provides meter data |
| **Valuation cell** | One computed money value for one time slot of one trade |
| **Partition** | A physical slice of a database table (we slice by delivery month) |
| **pg_partman** | PostgreSQL extension that auto-manages table partitions |
| **pg_cron** | PostgreSQL extension that runs scheduled jobs (like cleanup) |
| **Redis** | An in-memory cache for fast data access |
| **Kafka** | A message queue for event-driven communication between services |
| **Version** | A numbered revision of data; newer versions have higher numbers |
| **Version hash** | A fingerprint of all inputs that produced a cached value (for staleness detection) |
| **Input version set** | The exact list of versions of every input used to compute a value (for reproducibility) |

---

## 13. One-Page Summary for Validation

Use this checklist to validate the design with team members:

**Volume model:**
- [ ] **Three separate volume streams** (contractual, forecast, metered) — never mixed
- [ ] **Forecast and meter are per ASSET** — one wind farm = one forecast, one meter (not per trade)
- [ ] **Multiple trades can reference the same asset** — each with a multiplier (e.g., 30%, 40%)
- [ ] **Multiplier is applied at read time** — asset data stored once, trade share calculated on query
- [ ] **Sum of multipliers should be ~1.0** — over-allocation is flagged as a warning, not an error
- [ ] **Contractual series is per trade-leg** — this IS per trade (the contract is trade-specific)
- [ ] **Store data as-is** — no forced conversion to different time resolutions
- [ ] **Each stream versioned independently** — forecast v6 doesn't affect contractual v1

**Position & valuation:**
- [ ] **Position ledger is the legal record** — tracks who owes what, with full audit trail
- [ ] **Prices are formulas, not fixed numbers** — computed per interval using live market data
- [ ] **Three valuation types** — settlement (permanent), forward marks (temporary), EOD snapshots (permanent)
- [ ] **Forward marks are NOT stored permanently** — they're live estimates, overwritten constantly
- [ ] **Settlement only uses actual data** — never forecasts; always meter × multiplier
- [ ] **One asset event fans out to all trades** — forecast/meter update triggers revaluation for every trade referencing that asset

**Infrastructure:**
- [ ] **The system can always answer "what did we think on date X?"** — regulatory requirement
- [ ] **DST is handled correctly** — 23h/25h days produce correct energy
- [ ] **Aurora PostgreSQL 16** — no TimescaleDB
- [ ] **7-year retention then delete** — per REMIT/MiFID II/GDPR
