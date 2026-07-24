# Position & Valuation System — Plain Language Guide

**Purpose:** This document explains what our system does in everyday language, so anyone on the team (product, QA, ops, management) can understand and validate the design without reading the 1000-line technical specs.

**What this covers:**
1. What problem we're solving
2. How the system is structured (the big picture)
3. Volume Series — how we track electricity quantities
4. Position & Valuation — how we track money and risk
5. How the two halves talk to each other
6. Key rules the system must never break
7–12. Storage, design patterns, glossary
13. Beyond power — how this extends to gas, oil, and agriculture

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
    │   Unified model:    │   Trade ledger          │
    │   VolumeReference   │   + Price formulas      │
    │   → VolumeSeries    │   + Market prices       │
    │     × multiplier    │   + Calculated values   │
    │   + Metered actual  │                         │
    └─────────────────────┴─────────────────────────┘
                    │
            Connected by a
          "consumption contract"
         (the handshake rules)
```

---

## 3. Volume Series — Tracking Electricity Quantities

### 3.1 The Unified Volume Model

The system tracks electricity volumes using a **unified resolution pattern** where every trade works the same way. Two independent stores hold the data:

| Store | What it answers | Who fills it | Owned by | When it changes |
|---|---|---|---|---|
| **Volume Series** | "What volume do we expect for this trade?" | Trade capture OR weather models | **Per asset** (shared, for PPAs) or **Per trade** (dedicated, for DA/bilateral) | Depends: forecasts change constantly; trade profiles are locked after booking |
| **METERED ACTUAL** | "What actually flowed through the wire?" | The grid operator (TSO) sends meter data | **Per asset** (shared) | After delivery — first a rough number, later a confirmed one |

**Why separate?** Because they have completely different lives:
- Volume expectations (forecasts/profiles) are the basis for forward valuation
- Meter readings arrive late, get corrected, and are the basis for settlement

### 3.1a The Unified Resolution Pattern — The Key Insight

**Every trade resolves volume the SAME way:**

```
trade_volume = volume_series_interval.volume × multiplier
```

The only difference between trade types is the properties of what's being pointed to:

| Trade Type | Volume Series | multiplier | Example |
|---|---|---|---|
| **PPA** (long-term renewable) | Shared asset forecast (weather model) | 0.30 (trade's share) | "30% of the wind farm's output" |
| **DA fill** (exchange trade) | Dedicated per-trade profile (fixed MW) | 1.0 (100%) | "50 MW flat for tomorrow" |
| **Bilateral flat block** | Dedicated per-trade profile (fixed MW) | 1.0 (100%) | "25 MW Mon-Fri 08-20" |

**Analogy:** Think of it like a debit card vs a credit card at a store. The payment terminal processes them identically — swipe, amount, done. The fact that one draws from savings and the other from a credit line is invisible to the checkout process. Same for volume resolution: the code path is identical.

### 3.1b Assets vs Trades — A Critical Distinction

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

**Fixed-profile trades (DA, bilateral) are the simple case:** They don't reference an asset. Instead, the system creates a tiny per-trade "volume series" (e.g., 96 intervals of 50 MW for a DA block) and the trade references it with multiplier = 1.0. It's like a customer who owns the entire pizza — same resolution logic, just `100% × the whole thing`.

**What this means for the system:**
- The **forecast** is stored ONCE for the whole wind farm (e.g., "expect 72.5 MW at 18:45")
- The **meter reading** is stored ONCE for the whole farm (e.g., "measured 68.3 MW at 18:45")
- Each trade's share is calculated at read time: Trade T-7788 gets `72.5 × 0.30 = 21.75 MW`
- If the forecast updates, we store the update ONCE, and all three trades' valuations are recalculated
- For a DA trade: the volume series is tiny (96 intervals) and the multiplier is 1.0 — same code path

**Why not store separate copies per trade?** Because:
- 1 forecast update would require 3 writes instead of 1 (and for 50 trades on one asset, 50 writes)
- The original asset-level data would be lost (you can't add up trade shares if multipliers overlap or change)
- Changing a multiplier (e.g., selling 10% of your allocation to another buyer) would require rewriting all volume data

### 3.1c The "Degenerate Case" Concept

**This is what makes the model elegant.** A fixed-profile trade (DA fill, bilateral block) is NOT a different kind of thing — it's a *simpler* version of the same thing:

| PPA Trade | DA Trade |
|---|---|
| Points to a **shared** volume series (weather forecast) | Points to a **dedicated** volume series (fixed MW profile) |
| multiplier = 0.30 (partial share) | multiplier = 1.0 (whole thing) |
| Volume series has thousands of intervals (year-long) | Volume series has ~96 intervals (one day) |
| Forecast updates frequently (weather changes) | Profile is set once at trade capture |
| Settlement uses separate metered data | Settlement uses the same fixed profile |

Same resolution: `volume = series_interval × multiplier`. Same code. Same query. Same events.

**Parallel to pricing:** Just as a fixed price (EUR 45.00/MWh) is a "degenerate" pricing formula (a formula that just returns a constant), a DA fill's fixed volume profile is a "degenerate" volume series. The system doesn't special-case it — it processes it through the same formula engine, which just happens to return the same number every time.

### 3.2 What's Inside Each Volume Series

Each volume series stores a list of **time slots** with a power value. For example:

```
Volume Series for DA Fill T-5500 (seriesType=PROFILE):
  Apr 24, 2026  00:00-00:15  →  50 MW
  Apr 24, 2026  00:15-00:30  →  50 MW
  ... (96 slots for one day)

Volume Series for WindPark-Nordsee (seriesType=FORECAST):
  Aug 1, 2026   00:00-00:15  →  72.5 MW  (full farm output)
  Aug 1, 2026   00:15-00:30  →  71.8 MW
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

A 10-year solar contract has ~3.5 million time slots (10 years × 365 days × 96 quarter-hours/day). A single year has ~350K slots. We don't create them all at once. Instead:

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

Each volume series has its own version counter. When data changes:

- A new forecast arrives → forecast version goes from v5 to v6
- Meter data gets corrected → meter version goes from v1 to v2
- Trade profile amended → profile version goes from v1 to v2

These version numbers are independent. A new forecast (v6) doesn't change the meter version (still v1).

### 3.7 Quality States

Each series type tracks data quality:

| Series | Quality states | Meaning |
|---|---|---|
| PROFILE (fixed trades) | EFFECTIVE → AMENDED | "This is the active deal" vs "This was superseded by a change" |
| FORECAST (asset-linked) | CURRENT → SUPERSEDED | "This is the latest forecast" vs "A newer one replaced it" |
| Metered Actual | PROVISIONAL → VALIDATED; ESTIMATED (parallel) | "Rough reading (D+1)" → "Confirmed by the grid operator (D+7 to D+30)"; ESTIMATED is a **peer/substitute** state used when the TSO provides a gap-fill estimate instead of an actual reading — it is not a step after VALIDATED |

---

## 4. Position & Valuation — Tracking the Money

### 4.1 The Position Ledger (What We Owe / Are Owed)

The Position Ledger is the **legal record of our trading obligations**. For each trade:

- Who traded with whom
- How much power (signed: + means we buy, − means we sell)
- For which delivery period
- At what delivery point (which part of the grid)
- In which portfolio/book

### Critical property: Full audit trail (bitemporality)

The ledger answers not just "what's our position now?" but "what did we THINK our position was last Tuesday at 3pm?" This is required by European regulators (REMIT, MiFID II).

Example: If we correct a trade on Wednesday, we can still reconstruct what we believed on Monday — because the old version is never deleted, only superseded.

### Why bitemporal, not denormalized snapshots?

An alternative design would store a flat snapshot of every position × interval × value for each point in time — like taking a photo of the entire trading book every day. Here's why that doesn't work:

| Concern | Bitemporal ledger | Denormalized snapshots |
|---|---|---|
| **Storage per deal** | ~480–1,200 rows (10-year PPA) | ~12.8 billion rows (daily snapshots × 350K intervals/year × 10 years) |
| **"What did we know on date K?"** | Two-axis filter on existing rows | Requires a complete copy at every possible audit point |
| **Backdated correction** | Append a new version, close the old one | Either rewrite all historical snapshots (losing the original fact) or store both versions in every snapshot (reinventing bitemporality, but worse) |
| **Forward marks** | Ephemeral — zero durable rows | O(10⁹) rows per deal (every curve tick × every open interval) |
| **Fast current-state grid** | Slot cache + trade interval cache (rebuildable) | Native — but at massive storage cost |

The key insight: the ledger stores **facts** (what changed and when we knew it), not **views** (what the world looked like at time T). Facts are compact — a 10-year deal has ~24 ledger rows per year, multiplied by an amendment factor of 2–5×. Views are redundant — every snapshot duplicates all unchanged positions.

Where snapshot-like access is needed (the trader's grid, the EOD risk report), the system provides **rebuildable caches** (S6 slot cache, S6b trade interval cache) and one **deliberately frozen snapshot** (S5c EOD struck mark). These give the same fast reads without making redundant copies the source of truth.

### 4.2 Price Expressions (How to Calculate the Price)

Every trade has a price — but "price" means very different things depending on the deal. The system uses a single **PriceExpression** model that handles the full spectrum, from a trivial fixed price to a multi-index PPA formula. The key design insight: a fixed price is just a degenerate (simplest possible) formula. One code path handles everything.

#### The Spectrum: From Simple to Complex

**Level 1 — Fixed price (the simplest case)**

A day-ahead exchange fill or a bilateral block trade has a single agreed price:

```
Trade T-5500 (DA fill, 50 MW baseload for Apr 24, 2026):
  PriceExpression = Fixed(85.00 EUR/MWh)

  For every 15-minute interval:
    price = 85.00 EUR/MWh   (always the same number)
    value = 85.00 × volume × 0.25 hours
```

This is stored as a PriceExpression with a single constant leaf — a "tree" with just one node. The system processes it through the same formula engine as a complex PPA; it just happens to return the same number every time.

**Level 2 — Indexed price (one market reference)**

Some bilateral contracts tie the price to a market index:

```
Trade T-6600 (monthly forward, indexed to DA settlement):
  PriceExpression = Index(series="EPEX-DE-LU-DA15", lag=0)

  For each 15-minute interval:
    price = DA settlement price for that interval (looked up from Market Data Store S4)
    value = DA_price × volume × 0.25 hours
```

The expression has one leaf that references a market data series. The price is different for every interval — it depends on whatever the day-ahead auction cleared at.

**Level 3 — Indexed with spread/differential**

A common variation adds a fixed premium or discount to a market index:

```
Trade T-6700 (bilateral, DA + 3.50 EUR premium):
  PriceExpression = Add(
    Index(series="EPEX-DE-LU-DA15"),
    Fixed(3.50)
  )

  For each interval:
    price = DA_price + 3.50
```

Now the expression is a tree with two leaves (one index, one constant) and an arithmetic operator node.

**Level 4 — PPA with collar (floor + cap)**

Renewable PPAs typically add a price collar — a floor and cap that protect both parties:

```
Trade T-7000 (solar PPA, collar 40/90):
  PriceExpression = Clamp(
    min = Fixed(40.00),    ← floor: generator guaranteed at least 40 EUR
    max = Fixed(90.00),    ← cap: offtaker never pays more than 90 EUR
    inner = Index(series="EPEX-DE-LU-DA15")
  )

  For each interval:
    if DA_price < 40.00 → price = 40.00  (floor kicks in)
    if DA_price > 90.00 → price = 90.00  (cap kicks in)
    otherwise           → price = DA_price (pass-through)
```

**Level 5 — Full PPA formula (the reference deal)**

The reference deal (T-7788) shows the full complexity of a real-world wind PPA:

```
Trade T-7788 (wind PPA, collar + CPI escalation + negative-price gate):
  PriceExpression =
    NegativePriceGate(                          ← Step 4: if DA < 0, price = 0
      gate_input = Index("EPEX-DE-LU-DA15"),              (OVERRIDES the floor!)
      inner =
        Clamp(                                  ← Step 3: apply floor and cap
          min = Escalate(                       ← Floor = 42.00 × (CPI/base)
                  Fixed(42.00),
                  ratio = Divide(
                    Index("HICP", ref_month=delivery_year-1, month=November),
                    Fixed(105.2)
                  )
                ),
          max = Escalate(                       ← Cap = 95.00 × (CPI/base)
                  Fixed(95.00),
                  ratio = same CPI ratio
                ),
          inner = Index("EPEX-DE-LU-DA15")      ← Step 1: start with DA price
        )
    )
```

Worked example for interval 2026-08-15 18:45:

```
Step 1: DA price = 68.20 EUR/MWh
Step 2: CPI factor = HICP(2025-11) / 105.2 = 128.4 / 105.2 = 1.22053
         → Escalated floor = 42.00 × 1.22053 = 51.26 EUR
         → Escalated cap   = 95.00 × 1.22053 = 115.95 EUR
Step 3: Collar check: 51.26 ≤ 68.20 ≤ 115.95 → inside collar → price = 68.20
Step 4: Neg-price gate: DA ≥ 0 → gate does not fire → price stays 68.20
Result: 68.20 EUR/MWh × 11.40 MWh (metered) = 777.48 EUR
```

Contrast with interval 2026-08-15 03:15 (negative DA price):

```
Step 1: DA price = -14.90 EUR/MWh
Step 4: Neg-price gate: DA < 0 → gate FIRES → price = 0.00 EUR/MWh
         (the floor of 51.26 EUR is OVERRIDDEN — contract law, not math)
Result: 0.00 EUR × volume = 0.00 EUR
```

**Level 6 — Multi-index PPA (multiple market references)**

Some PPAs reference multiple curves — one for forward valuation, a different one for settlement:

```
Trade T-8200 (PPA with different forward and settlement curves):
  PriceExpression for FORWARD valuation:
    Clamp(
      min = Escalate(Fixed(38.00), CPI ratio),
      max = Escalate(Fixed(88.00), CPI ratio),
      inner = Index("EEX-DE-POWER-FRONT-MONTH")    ← forward curve for undelivered
    )

  PriceExpression for SETTLEMENT:
    Clamp(
      min = Escalate(Fixed(38.00), CPI ratio),
      max = Escalate(Fixed(88.00), CPI ratio),
      inner = Index("EPEX-DE-LU-DA15")              ← DA settlement for delivered
    )
```

The expression tree can reference ANY number of market data series as leaves. Each leaf points to a specific curve/index in the Market Data Store (S4), with its own publication frequency, quotation window, and version history.

#### How It All Fits Together

```
THE PRICE EXPRESSION MODEL
═══════════════════════════

Fixed price           Index price          PPA collar         Full PPA formula
(simplest)            (one curve)          (floor + cap)      (reference deal)

  [85.00]              [DA15]             Clamp               NegGate
                                         ╱  │  ╲             ╱      ╲
                                      [40] [DA15] [90]    [DA15]   Clamp
                                                                  ╱  │  ╲
                                                          Escalate [DA15] Escalate
                                                          ╱    ╲          ╱    ╲
                                                       [42]  CPI_ratio [95]  CPI_ratio
                                                              ╱  ╲           ╱  ╲
                                                          [HICP] [105.2] [HICP] [105.2]

    ↓                    ↓                  ↓                  ↓
 1 leaf               1 leaf             3 leaves           5+ leaves
 0 operators          0 operators        1 operator         4+ operators
 Same code path for ALL of them — the "tree walker" just has less to walk.
```

**Why this matters:**
- **One code path** resolves all prices — no `if (fixedPrice) ... else if (indexed) ... else if (PPA) ...`
- Adding a new operator (e.g., time-of-use shaping, basis differential for gas) means adding a new node type to the tree — existing formulas are untouched
- Every resolved value records **which leaves actually mattered** (`active_leaves`): if the collar is inside, CPI was referenced but didn't change the result; if the neg-gate fired, nothing below it mattered. This drives the dependency index — when HICP is restated, only cells where CPI was active need recomputation

The system stores the **recipe** (the formula tree), not pre-calculated prices. Prices are computed per interval when needed, using the actual market data available at that time. Same inputs always produce the same output — this is what makes settlement values reproducible for regulatory audits.

### 4.3 Three Types of Valuation

| Type | What it answers | Stored how | Changes? |
|---|---|---|---|
| **Settlement** (realized) | "We delivered power in slot X. Actual price × actual volume = actual money." | Permanently, with full audit trail | Only if inputs are corrected |
| **Forward mark** (unrealized) | "For future slots, current curve × forecast volume = expected money." | Temporarily — overwritten every time the curve moves | Constantly (it's a live estimate) |
| **End-of-Day struck mark** | "Official daily snapshot of our risk exposure." | Permanently, per month bucket per business day | Never (it's a frozen snapshot) |

**Why this split matters:** Without it, storing live marks for every 15-minute slot for every trade would create billions of rows. By keeping forward marks ephemeral (just the latest number), we avoid a storage explosion.

**Why volume intervals are NOT bitemporal (and how to see past values)**

The position ledger uses full bitemporality (two time axes — see §4.1 above) because trade amendments are frequent, often backdated, and must be audit-reconstructable at any point. Volume intervals have a simpler lifecycle, so they use a lighter versioning model:

| Series type | How it versions | "What was the value 1 month ago?" |
|---|---|---|
| **PROFILE** (DA/bilateral) | **Immutable** — intervals never change after creation | Look at the same row — it hasn't changed. If the trade was amended, a new series version was created alongside the old one (the old intervals still exist). Query by `transaction_time` on the series header. |
| **FORECAST** (asset forecasts) | **Whole-series replacement** — each re-forecast creates a new version | Find the series version whose `transaction_time` is before your target date. That version's intervals are the answer. |
| **METERED_ACTUAL** (meter data) | **Forward-link chain** — corrections append new interval rows with `supersedes_id` pointing to the old | Follow the chain backward from the current interval to the version that existed before your target date. |

The key insight: versioning happens at the **series header level** (which version was active at time T?), not at the interval level (no `known_from/known_to` on each 15-minute row). A 10-year deal has maybe 5–10 series versions but ~3.5 million intervals — adding bitemporal columns to every interval would multiply storage by the amendment factor for no benefit, since the series header already tells you which version was active when.

When a settlement cell needs to be reproduced ("what inputs did we use?"), it records the volume `version_id` in its input-version-set. The position ledger's bitemporality and the volume series' version chain work together — two mechanisms, each suited to its lifecycle.

**Real-world PPA amendment frequency (why series-level versioning is sufficient)**

A typical 7–15 year PPA sees 5–15 amendments over its entire life — far fewer than might be assumed:

| Amendment type | How often (over contract life) | Affects volume? |
|---|---|---|
| Counterparty / admin corrections | 2–5× | No — ledger + expression only |
| Allocation changes (multiplier ramp-up, partial unwind) | 1–3× | Yes — new VolumeReference, not new intervals |
| Price expression amendments (cap/floor, index rebasing) | 1–2× | No — expression only |
| Delivery window changes (extension, early termination) | 0–1× | Yes — new series version |
| Novation (counterparty M&A, credit event) | 0–1× | No — ledger only |
| Force majeure / regulatory | 0–1× | Possibly — new series version |

Most amendments (counterparty fixes, price changes) **don't touch volume at all**. Of those that do, allocation changes update the `VolumeReference.multiplier` — the underlying intervals stay the same. Only delivery window changes and trade restructurings create new series versions with new intervals.

This means a 10-year PPA realistically has **2–3 volume series versions**, not dozens. The storage math:

| Approach | Rows for a 10-year PPA |
|---|---|
| Series-version chain (current design) | 3 versions × 3.5M intervals = **~10.5M rows** (only latest queried on hot path) |
| Interval-level bitemporality | 3.5M intervals × 4 extra timestamp columns × 3 versions = **same row count but 30% wider rows**, with complex query filters on every read |

Series-level versioning gives the same audit capability with simpler queries, smaller rows, and no overhead on the hot-path reads that happen thousands of times per second.

### Worked example: 15-year wind PPA with 12 amendments — full row-count calculation**

Reference deal: 15-year PPA on asset WP-NORDSEE, 30% allocation, 15-min granularity, delivery 2026–2041.

*Step 1: Base interval count*

```
Intervals per year = 365 days × 96 intervals/day     = 35,040   (normal year)
                     366 days × 96 intervals/day     = 35,136   (leap year)
DST adjustment:      +4 intervals (autumn) −4 (spring) = net 0/year

15 years (2026–2041, including leap years 2028/2032/2036/2040):
  11 normal years × 35,040  = 385,440
   4 leap years   × 35,136  =  140,544
                    Total   =  525,984 intervals ≈ 526K per series version
```

*Step 2: Which amendments create new volume series versions?*

Assume 12 amendments over 15 years (middle of the 8–15 range):

```
Amendment timeline                              Volume series version?
─────────────────────────────────────────────── ─────────────────────
Year 1:  Counterparty EIC code correction       No  (ledger only)
Year 2:  Multiplier ramp-up 0.20 → 0.30        No  (VolumeReference only — same intervals)
Year 3:  Price cap renegotiated to 92 EUR       No  (expression only)
Year 4:  Portfolio reassignment                 No  (ledger only)
Year 5:  Grid connection delay → delivery       YES — new PROFILE version (v2)
         start shifts 6 months forward               ~509K intervals (15.5yr→14.5yr remaining)
Year 6:  Partial unwind of 10% allocation       No  (new VolumeReference with multiplier 0.20,
                                                     effectiveTo shortened — same intervals)
Year 8:  Novation to new counterparty (M&A)     No  (ledger only)
Year 9:  HICP index base year rebased           No  (expression only)
Year 10: Delivery extended 1 year (to 2042)     YES — new PROFILE version (v3)
                                                     ~561K intervals (adds ~35K for extra year)
Year 11: Price floor removed                    No  (expression only)
Year 13: Force majeure curtails last 2 years    YES — new PROFILE version (v4)
                                                     ~491K intervals (delivery_end pulled back)
Year 14: Counterparty legal entity rename       No  (ledger only)
```

Result: **12 amendments → only 3 create new PROFILE series versions**

*Step 3: PROFILE interval row count (per trade)*

```
Version 1 (original, years 1–5):     526K intervals  (quality_state = AMENDED after v2)
Version 2 (grid delay, years 5–10):  509K intervals  (quality_state = AMENDED after v3)
Version 3 (extension, years 10–13):  561K intervals  (quality_state = AMENDED after v4)
Version 4 (curtailment, years 13+):  491K intervals  (quality_state = EFFECTIVE — current)
                                     ─────
                              Total: ~2.09M rows in volume_interval table
```

Only version 4 (491K rows) is queried on the hot path. Versions 1–3 are retained for audit but never touched by live queries.

*Step 4: FORECAST series (per asset — shared across all trades on WP-NORDSEE)*

The FORECAST series is independent of trade amendments. It's driven by weather model refreshes (NWP — Numerical Weather Prediction). The refresh cadence depends on asset type and time horizon:

```
                        Solar asset              Wind asset
                        ───────────              ──────────
Near-term (M+0…M+2):   Daily (satellite/NWP)    Daily–weekly (NWP model runs)
Medium-term (M+3…M+12):Weekly–monthly (P50/P75) Monthly (calibrated yield)
Far-term (M+13+):      Quarterly (TMY-based)    Quarterly (TMY-based)

The CTRM does NOT store every intraday NWP run. It stores the
"best-available generation forecast for forward valuation" — typically
the latest day-ahead or week-ahead forecast that the asset operator
publishes into the system.
```

**Solar worst-case calculation (daily near-term refresh):**

```
Near-term (3 months × 15 years):
  Daily refresh × 3 months materialized = 365 versions/year
  But: only the near-term window triggers daily refreshes.
  Near-term intervals per version: ~3 months × 30.4 × 96 = ~8,755

Medium-term (9 months × 15 years):
  Monthly refresh = 12 versions/year
  Each covers a 9-month window but only the CHANGED intervals
  are re-materialized (partial supersession, not full replacement).
  Medium intervals per version: ~9 × 30.4 × 96 = ~26,266
  However: partial supersession means ~30% of intervals change
  per refresh → ~7,880 net new rows per version

Far-term (remaining years):
  Quarterly refresh = 4 versions/year
  Intervals per version: remainder of delivery window
  At far dates, rolling materialization means few intervals
  are actually materialized. ~2,000 per version.

Blended annual version count:
  Near:   365 versions × 8,755 intervals  = ~3.20M/year
  Medium: 12  versions × 7,880 intervals  = ~0.09M/year
  Far:    4   versions × 2,000 intervals  = ~0.01M/year
                                    Total  = ~3.30M/year

Over 15 years: ~3.30M × 15 = ~49.5M rows (SOLAR WORST CASE)
```

**Wind realistic calculation (weekly near-term refresh):**

```
Near:   52 versions/year × 8,755 intervals = ~0.46M/year
Medium: 12 versions/year × 7,880 intervals = ~0.09M/year
Far:     4 versions/year × 2,000 intervals = ~0.01M/year
                                      Total = ~0.56M/year

Over 15 years: ~0.56M × 15 = ~8.4M rows (WIND TYPICAL)
```

Only the **latest version's** intervals are queried for forward marks. Old versions are SUPERSEDED and retained for audit. Crucially, the forecast is shared by ALL trade-legs on this asset — not duplicated per trade. If 5 PPAs share one solar asset, the 49.5M rows serve all 5 trades.

*Step 5: METERED_ACTUAL intervals (per asset — accumulates progressively)*

Meter data arrives after delivery, not all at once:

```
Years with metered data:    Assume 10 years delivered so far (years 1–10 of 15)
Intervals per year:         ~35,040
Quality transitions:        Most intervals get v1 (PROVISIONAL) + v2 (VALIDATED)
                           ~5% get a v3 (ESTIMATED gap-fill or late correction)
Average version factor:     ~2.05 per interval

Total METERED_ACTUAL:       10 × 35,040 × 2.05 = ~718K rows
```

*Step 6: Full row count for this deal*

```
Layer                   Table                         Rows        Notes
──────────────────────  ─────────────────────────── ──────────  ─────────────────────────
PROFILE intervals       volume_interval               2.09M     4 versions; only latest queried
FORECAST intervals      volume_interval              8.4–49.5M  Wind (weekly) to solar (daily);
  (per asset, shared)                                            shared across all trades on asset
METERED_ACTUAL          metered_actual_interval        0.72M     Progressive; shared across trades
Series headers          volume_series                      4     One per PROFILE version
Series headers          volume_series (FORECAST)     840–5,475   Wind (weekly) to solar (daily)
Series headers          metered_actual_volume_series     ~20     One per meter batch version
VolumeReference         volume_reference                 2–3     Original + partial unwind
Position ledger         (separate module)               ~720     180 months × ~4× amendment factor
                                                    ──────────
                                        Total:  11.2–52.3M rows (intervals only)

Solar worst case dominates. But: the FORECAST rows are PER ASSET, not per
trade. 5 PPAs on the same solar asset share the 49.5M FORECAST rows.
Per-trade exclusive storage is only ~2.09M (PROFILE) + share of FORECAST.
```

*Step 7: What if we had used interval-level bitemporality instead?*

```
Solar worst case: ~52.3M interval rows, with bitemporality:
  + 4 extra TIMESTAMPTZ columns per row (valid_from, valid_to, known_from, known_to)
  + 32 bytes × 52.3M = ~1.67 GB additional raw storage (per asset)
  + Composite index on (tenant_id, series_key, interval_start, known_from, known_to)
    adds ~100 bytes per row = ~5.23 GB additional index storage
  + Every hot-path query gains:  AND known_from <= :asOf AND known_to > :asOf
    — an extra range filter on the highest-volume read path

Cost of bitemporality:  ~6.9 GB extra storage per asset + query complexity
Benefit:                Zero — series-header transaction_time already answers
                        "which version was active at time T?"

For 200 tenants × ~5 solar assets each = 1,000 assets:
  6.9 GB × 1,000 = ~6.9 TB of unnecessary index/column overhead
```

The series-version chain achieves the same audit capability at zero additional per-row cost. The high FORECAST version count for solar assets makes this decision even more impactful than for wind.

*Step 8: What if we had used denormalized position snapshots instead?*

The most common alternative to bitemporality is a **position snapshot** — a flat table storing `(trade, interval, position_mw, volume, price, value, snapshot_date)` for every interval at every observation point. Think of it as "taking a photograph of the entire trading book" at regular intervals.

**What triggers a new snapshot?**

In a snapshot design, you must re-snapshot the full interval set whenever ANY input changes:

```
Trigger                         Frequency (solar PPA, 15yr)
──────────────────────────────  ──────────────────────────────
Trade amendment                 12 over contract life
Forecast refresh (solar)        Daily → 5,475 over 15 years
Meter data arrival              Daily (delivered periods) → ~3,650
Price curve tick (DA/forward)   Daily EOD at minimum → 5,475
                                ─────────────────────────────
Total snapshot events:          ~14,600 (many overlap on the same day)
De-duplicated to daily EOD:     ~5,475 unique snapshot days
```

**How many intervals per snapshot?**

At any given snapshot point, the deal has ~526K intervals spanning its full delivery window. Each snapshot must capture the resolved position for every interval:

```
Snapshot row = (trade_leg_id, interval_start, interval_end,
               position_mw, resolved_volume, multiplier,
               price, settlement_value, forward_mark_value,
               snapshot_date)

Rows per snapshot:              ~526K (full delivery window)
Snapshots over 15 years:        5,475 (daily EOD)
                                ──────
Rows per trade:                 526K × 5,475 = ~2.88 BILLION rows
```

**Platform-scale comparison (200 tenants × 20 PPA trades each = 4,000 trades):**

```
Approach                    Per trade     Per asset      Platform total
──────────────────────────  ───────────   ────────────   ─────────────────
CURRENT DESIGN
  Position ledger (S1)      720 rows      —              2.88M rows
  PROFILE intervals         2.09M         —              8.36B rows
  FORECAST intervals        (shared) →    49.5M          49.5B rows (1K assets)
  METERED_ACTUAL            (shared) →    718K           718M rows (1K assets)
  S6b trade interval cache  ~500K         —              2.0B rows (rebuildable)
                            ─────────     ────────────   ─────────────────
  Total:                    ~2.8M/trade   ~50.2M/asset   ~60.8B rows

DENORMALIZED SNAPSHOTS
  Position+interval snapshot 2.88B/trade  —              11.5 TRILLION rows
                            ─────────     ────────────   ─────────────────
  Ratio:                    ~1,029×                      ~189×
```

**Why the explosion happens:**

The snapshot approach has a fundamental problem: it **multiplies the number of intervals by the number of observation points**. In the current design:

1. **Position ledger** is sparse (~24 rows/year) and versioned only on amendment (~12 over 15 years) → ~720 rows
2. **Volume intervals** are versioned at the series level (only 2–4 versions over 15 years for PROFILE) → ~2.09M rows
3. **Forecast changes** create new series versions but the old intervals stay — they're not duplicated into every trade → shared 49.5M rows across all trades on the asset

In the snapshot approach, a daily forecast refresh forces a re-snapshot of 526K intervals × 4,000 trades that reference forecasts. That's 2.1 billion new rows per day just from forecast updates.

```
Daily write load comparison:
  Current:    ~8,755 new FORECAST intervals (one series version, shared)
  Snapshots:  526K intervals × 4,000 trades = ~2.1 BILLION rows/day

Storage after 1 year:
  Current:    ~3.3M FORECAST rows (solar asset) + ~2.09M PROFILE (per trade)
  Snapshots:  365 days × 526K × 4,000 trades = ~769 BILLION rows

Storage cost (Aurora @ $0.10/GB-month, ~200 bytes/row):
  Current:    ~1.1 GB → ~$0.11/month
  Snapshots:  ~143 TB → ~$14,300/month
```

**What you lose beyond storage:**

- **Query complexity:** "What was the position on July 1?" requires scanning the snapshot for that date — O(intervals) per query. The bitemporal ledger answers with O(1) version lookup.
- **Write amplification:** Every forecast update writes 526K × N_trades rows. The current design writes ~8,755 rows (one series version).
- **No sharing:** Each trade gets its own copy of the forecast × multiplier result. The current design stores the forecast once per asset and applies the multiplier at read time.
- **Backdated corrections:** Rewriting historical snapshots corrupts the audit trail. The bitemporal ledger appends a new version without touching old rows.

**Expected query performance comparison (structural analysis — no production data exists yet)**

There are no benchmarks for this system (it's in design phase). The numbers below are **structural estimates** based on PostgreSQL B-tree index lookup characteristics on Aurora, not measured timings. They illustrate the architectural difference, not a performance guarantee.

```
Query: "What is trade T-7788's resolved volume for Aug 15, 2026?"
        (96 quarter-hour intervals for one day)

CURRENT DESIGN (3-join: volume_reference → volume_series → volume_interval)
─────────────────────────────────────────────────────────────────────────────
  1. Index lookup on volume_reference (trade_leg_id)    → 1 row       O(log n), n ≈ 5K
  2. Index lookup on volume_series (series_key, ver)    → 1 row       O(log n), n ≈ 5.5K
  3. Range scan on volume_interval (series_id, start)   → 96 rows     O(log n + 96)
  Total rows touched: 98
  Index depth: 2–3 levels per lookup (B-tree on thousands of rows)
  Design-target SLA: p95 = 15 ms, p99 = 40 ms (V2.0 §14.3)

DENORMALIZED SNAPSHOT
─────────────────────────────────────────────────────────────────────────────
  1. Index lookup on snapshot table (trade_leg, snapshot_date, interval_start)
     → 96 rows, BUT index is over 2.88 BILLION rows (15-year deal)
  Total rows touched: 96
  Index depth: 6–7 levels (B-tree on billions of rows)
  Estimated: 3–5× slower per lookup due to deeper index tree +
             larger working set that won't fit in Aurora buffer cache

Query: "What was T-7788's position as known on July 1?" (audit/regulatory)
        (all 526K intervals, resolved at a historical point)

CURRENT DESIGN
─────────────────────────────────────────────────────────────────────────────
  1. Bitemporal filter on position_ledger (known_from ≤ July 1)  → ~12 rows
     (180 month-blocks, but only ~12 affected by amendments before July 1)
  2. Version lookup on volume_series (transaction_time ≤ July 1) → 1 header
  3. Full scan of that version's intervals                       → 526K rows
  Total rows touched: ~526K (the intervals themselves — unavoidable)
  The ledger + version lookup is O(log n) on small tables (~720 and ~5.5K rows)

DENORMALIZED SNAPSHOT
─────────────────────────────────────────────────────────────────────────────
  1. Scan snapshot table where snapshot_date = July 1, trade = T-7788
     → 526K rows, BUT index is over 2.88B rows
  Total rows touched: 526K (same interval count)
  The difference: index lookup into a 2.88B-row table vs a 720-row table +
  5.5K-row table. Buffer cache pressure is orders of magnitude higher.
  On cold storage (audit queries hit warm/cold data), the snapshot approach
  may require disk I/O for index pages that the current design never needs.

Query: "Net position for zone DE_LU, portfolio Wind, Aug 15"
        (slot cache / grid display — the trader's primary screen)

CURRENT DESIGN
─────────────────────────────────────────────────────────────────────────────
  Read from pre-built S6 slot cache or S6b trade interval cache.
  Direct index lookup → 96 rows. No joins. No version filtering.
  Design-target SLA: p95 = 5 ms (S6b), p95 = 30 ms (full 3-join, §14.3)

DENORMALIZED SNAPSHOT
─────────────────────────────────────────────────────────────────────────────
  Same query, but the snapshot table IS the "cache" — and it's 11.5T rows
  platform-wide. No separation between hot-path reads and audit history.
  Every trader grid query competes with audit scans in the same table.
```

**Important caveat:** These are structural comparisons (index depth, rows touched, working set size), not benchmarked timings. Actual performance depends on Aurora instance size, buffer pool hit rates, concurrent load, and query plan choices. The design-target SLAs in V2.0 §14 will be validated during the implementation phase.

The current design achieves the same query capability — "show me the position as of date T" — by combining the bitemporal ledger's `known_from/known_to` filter with the volume series' `transaction_time` + `version_id` chain. Two lightweight mechanisms that together replace trillions of redundant snapshot rows.

### 4.3a Regulatory Reporting — What Regulators Actually Ask For

A common question is: "Do regulators need 15-minute interval data?" The answer is **no — regulators ask at trade and position grain, not interval grain.** Understanding this validates why the bitemporal ledger is grained at trade-leg × delivery-month (not at intervals), and why interval-level bitemporality is unnecessary.

#### EU energy trading regulatory landscape

| Regulation | What it governs | Reporting grain | Frequency |
|---|---|---|---|
| **REMIT** (EU 1227/2011) | Wholesale energy market integrity | **Trade-level** | T+1 (next business day) to ACER |
| **REMIT Art. 8** (on request) | Market surveillance investigations | **Position-level** (reconstruct at any historical point) | Ad hoc — ACER can request at any time |
| **EMIR** (EU 648/2012) | OTC derivative clearing & reporting | **Trade-level** + **daily valuation** | T+1 (trade), daily (mark-to-market) |
| **MiFID II / MiFIR** | Transaction reporting & position limits | **Trade-level** (T+1); **aggregated position** (daily/weekly to NCA) | T+1 transactions; weekly public position reports |
| **MAR** (EU 596/2014) | Market abuse prevention | **Reconstruct-on-demand** | Ad hoc investigation — "show your position at time T" |
| **BNetzA** (German national) | Energy market oversight | **Operational** (nominations, schedules) | Varies — volume-series-side, not position |
| **GDPR** | Data retention limits | **Retention ceiling** | Ongoing — delete after 5–7 year regulatory period |

#### What regulators never ask

No regulator asks: *"What was your MW at 14:15 on August 15?"*

What they do ask:

```
REMIT trade report:
  "Report all trades executed today."
  → Trade-level: parties, product, price, quantity, delivery period, venue
  → NOT per-interval. A 15-year PPA is ONE trade report, not 5.26M interval reports.

EMIR daily valuation:
  "What is the current mark-to-market of each derivative position?"
  → Per trade × delivery-month bucket × business day = ~180 rows for a 15-year deal
  → This maps directly to the EOD struck mark (S5c)

ACER market surveillance:
  "Reconstruct your net position for DE_LU power, August 2026, as known on September 5."
  → Bitemporal filter: valid_from ≤ Aug-01, valid_to > Aug-01, known_from ≤ Sep-05
  → Returns ~1 row per trade in that delivery month
  → The regulator wants the POSITION, not the 2,976 quarter-hours behind it

Position limits (MiFID II):
  "What is your aggregate net long/short position in this commodity derivative?"
  → Sum of net positions across all delivery months
  → Monthly grain, aggregated across trades

MAR investigation:
  "Reproduce the exact state of your trading book at 14:00 on July 3."
  → Bitemporal filter at knowledge-time = July 3 14:00
  → Returns all positions as they were believed at that moment
  → This is the core REMIT/MAR use case that REQUIRES bitemporality (FR-007)
```

#### How each regulatory question maps to the system

| Regulatory question | System component | Grain | Rows touched (15-year PPA) |
|---|---|---|---|
| REMIT trade report | Trade entity (external to volume module) | 1 row per trade | 1 |
| EMIR daily valuation | S5c EOD struck mark | position × month-bucket × business day | ~180 (15yr × 12 months) |
| Position reconstruction at date K | S1 Position Ledger (bitemporal) | trade-leg × delivery-month | ~720 (~180 blocks × ~4 versions) |
| Settlement reproduction | S5a settlement cell + input-version-set | position × interval (delivered only) | ~526K intervals, but only for the specific period requested |
| Position limits | S6/S7 rollups, or re-aggregate from ledger | aggregated net per contract | ~180 rows (monthly buckets) |
| MAR full book reconstruction | S1 bitemporal filter at knowledge-time K | all positions as-of K | O(active trades × months), typically <100K rows |

#### Why interval-level data is NOT regulatory — it's operational

Interval-level data (the 526K quarter-hours in a 15-year PPA) serves **internal** purposes:

1. **Computing** the settlement value (interval × price → value, summed into the settlement cell that IS reported)
2. **Resolving** the forward mark (forecast × multiplier → per-interval exposure, rolled up into the struck mark that IS reported)
3. **Displaying** the trader grid (slot cache — a convenience view, never reported)
4. **Reconciliation** between TSO meter data and traded positions

The regulatory output is always **aggregated**: a trade report, a monthly mark, a net position. The intervals are the ingredients; the regulation asks for the finished dish.

This is precisely why:
- The **position ledger** is bitemporal at trade-leg × delivery-month grain (D-1) — that's the grain regulators ask for
- **Volume intervals** use series-level version chains (FR-009c) — regulators never query intervals directly; they're consumed through the settlement cell's input-version-set (FR-056) when reproducing a specific settlement
- **EOD struck marks** (S5c) are the one sanctioned snapshot (FR-079) — because EMIR daily valuation is genuinely a daily snapshot requirement, but at monthly-bucket grain, not interval grain
- **Forward marks** are ephemeral (FR-075) — because no regulator asks "what was your intraday mark at 14:32?" (that question has no regulatory standing)

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

**Unified resolution — same for all trades:**

| Valuation purpose | Trade type | What the system reads | How |
|---|---|---|---|
| Forward marks (future) | PPA (asset-linked) | `VolumeReference.volumeSeriesKey` → FORECAST × multiplier | Asset forecast × 0.30 |
| Forward marks (future) | DA / bilateral | `VolumeReference.volumeSeriesKey` → PROFILE × 1.0 | Trade profile × 1.0 |
| Settlement (past) | PPA (asset-linked) | `VolumeReference.meteredSeriesKey` → METERED_ACTUAL × multiplier | Asset meter × 0.30 |
| Settlement (past) | DA / bilateral | `VolumeReference.volumeSeriesKey` → PROFILE × 1.0 | The traded volume IS the settled volume (no meter exists by design) |

The system always looks up the `VolumeReference` to find the volume series key AND the multiplier, then computes `series_volume × multiplier`.

**Important:** DA/bilateral trades reading their PROFILE series for settlement is NOT a "fallback" — it is their designed resolution path. These trades have no physical meter (`meteredSeriesKey` is null by design), so the traded profile volume is the settled volume.

### 5.2 No Mixing

For asset-linked trades (PPAs), if meter data isn't available yet, the system does NOT substitute the forecast. It just says "no settlement value yet — waiting for meter." Each volume source answers a different question (what is expected vs what was measured), and mixing them would corrupt the realized/unrealized classification.

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

### 6.3 Series Don't Interfere

Updating the forecast never changes metered data, and vice versa. Amending a trade's PROFILE series never changes the asset's FORECAST series. Each series lives in its own world.

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
| EMIR | Trade reporting records; 5-year minimum |
| GDPR | Delete after that |

After 7 years, monthly partitions are automatically dropped.

### 8.3 Scale

For 200 customers with ~20 long-term contracts each:
- ~77M–119M rows of volume data in the "active" partitions (unified model)
- This is well within Aurora's capabilities with proper indexing

---

## 9. Data Models — What Gets Stored Where

This section gives the team a mental map of the database tables, their relationships, and what data lives in each. Think of it as the "blueprint of the warehouse" before we build it.

### 9.1 The Full Table Map

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         VOLUME SERIES SIDE                                   │
│                                                                              │
│   UNIFIED MODEL: Every trade → VolumeReference → VolumeSeries × multiplier   │
│                                                                              │
│  ┌───────────────────────────────────────┐  ┌────────────────────────────┐   │
│  │ volume_series (UNIFIED)               │  │ metered_actual_            │   │
│  │                                       │  │ volume_series              │   │
│  │  - series_key                         │  │ (per asset only)           │   │
│  │  - series_type (FORECAST | PROFILE)   │  │                            │   │
│  │  - asset_id     (set for FORECAST)    │  │  - series_key              │   │
│  │  - trade_leg_id (set for PROFILE)     │  │  - asset_id                │   │
│  │  - version_id                         │  │  - version_id              │   │
│  │  - granularity                        │  │  - granularity             │   │
│  │  - delivery_start/end                 │  │  - delivery_start/end      │   │
│  │  - delivery_timezone                  │  │  - delivery_timezone       │   │
│  │  - volume_unit (MW/MWh)               │  │  - volume_unit             │   │
│  │  - rated_capacity_mw (FORECAST only)  │  │  - rated_capacity_mw       │   │
│  │  - forecast_source_id (FORECAST only) │  │  - metering_point_id       │   │
│  │  - quality_state                      │  │  - quality_state           │   │
│  │  - published_at                       │  │  - received_at             │   │
│  └──────────────┬────────────────────────┘  └──────────┬─────────────────┘   │
│                 │ 1:many                               │ 1:many              │
│                 ▼                                      ▼                     │
│  ┌───────────────────────────────────────┐  ┌────────────────────────────┐   │
│  │ volume_interval (UNIFIED)             │  │ metered_actual_interval    │   │
│  │                                       │  │ (FULL ASSET output)        │   │
│  │  - interval_start                     │  │                            │   │
│  │  - interval_end                       │  │  - interval_start          │   │
│  │  - volume (MW or MWh)                 │  │  - interval_end            │   │
│  │  - energy (MWh derived)               │  │  - volume (MW or MWh)      │   │
│  │  - status                             │  │  - energy (MWh derived)    │   │
│  │  - chunk_month                        │  │                            │   │
│  └───────────────────────────────────────┘  └────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ volume_reference (ALL TRADES — THE KEY TABLE)                           │ │
│  │                                                                         │ │
│  │  - trade_leg_id        (which trade — ALWAYS set)                       │ │
│  │  - asset_id            (which asset — null for fixed-profile trades)    │ │
│  │  - multiplier          (0.30 for PPAs; 1.0 for DA/bilateral)            │ │
│  │  - effective_from/to   (OWN date range — not derived from trade)        │ │
│  │  - volume_series_key   (points to the VolumeSeries)                     │ │
│  │  - metered_series_key  (points to MeteredActual — null for DA)          │ │
│  │  - volume_formula_id   (→ separate volume_formula table: tolerance,     │ │
│  │                          seasonal adjustments, shaping entries)         │ │
│  │                                                                         │ │
│  │  *** EVERY trade goes through this table ***                            │ │
│  │  Volume = series_interval × multiplier (computed at read time)          │ │
│  │  PPAs:  multiplier < 1.0, shared asset series                           │ │
│  │  DA:    multiplier = 1.0, dedicated per-trade series (few intervals)    │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌─────────────────────────┐                                                 │
│  │ compaction_view         │  (Optional summary — created on user request)   │
│  │  - source_series_id     │                                                 │
│  │  - target_granularity   │                                                 │
│  │  └→ compacted_interval  │                                                 │
│  └─────────────────────────┘                                                 │
└──────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                      POSITION & VALUATION SIDE                              │
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
│  │  - quantity_ref ─────────────────────────────────────→ volume_series_key │
│  │  - delivery_point    │   │  - known_from/to     │                        │
│  │  - portfolio / book  │   │                      │                        │
│  │  - delivery_range    │   └──────────────────────┘                        │
│  │  - price_expr_ref ──────────────────┘                                    │
│  │  - volume_unit       │                                                   │
│  │  - valid_from/to     │  (bitemporal: "when true" + "when known")         │
│  │  - known_from/to     │                                                   │
│  └──────────┬───────────┘                                                   │
│             │                                                               │
│             │ feeds into (derived)                                          │
│             ▼                                                               │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐   │
│  │settlement_cell (S5a) │  │forward_mark (S5b)    │  │eod_struck_mark   │   │
│  │(real money, durable) │  │(estimates, ephemeral)│  │(S5c, frozen)     │   │
│  │                      │  │                      │  │                  │   │
│  │ - position_id        │  │ - position_id        │  │ - position_id    │   │
│  │ - interval           │  │ - interval           │  │ - delivery_month │   │
│  │ - value (EUR)        │  │ - value (EUR)        │  │ - business_day   │   │
│  │ - status (PROV/FINAL)│  │ - (overwritten!)     │  │ - value          │   │
│  │ - input_version_set  │  │                      │  │ - input_versions │   │
│  │ - active_leaves      │  │                      │  │ - (immutable!)   │   │
│  │ - valid/known times  │  │                      │  │                  │   │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────┘   │
│                                                                             │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐   │
│  │ slot_cache (S6)      │  │trade_interval_cache  │  │dependency_index  │   │
│  │ (net grid display)   │  │(S6b, per-trade detail│  │(S8, routing)     │   │
│  │                      │  │                      │  │                  │   │
│  │ - delivery_point     │  │ - trade_leg_id       │  │ - input_series   │   │
│  │ - portfolio          │  │ - interval_start     │  │ - cell_id        │   │
│  │ - interval           │  │ - resolved_mw        │  │ - active_leaves  │   │
│  │ - net_mw             │  │ - resolved_mwh       │  │                  │   │
│  │ - net_mwh            │  │ - multiplier         │  │ "When input X    │   │
│  │ - version_hash       │  │ - version_hash       │  │  changes, which  │   │
│  │                      │  │                      │  │  cells to redo?" │   │
│  │ (rebuildable cache)  │  │ (opt-in, rebuildable)│  │ (rebuildable)    │   │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────┘   │
│                                                                             │
│  ┌──────────────────────┐                                                   │
│  │ rollup_aggregate (S7)│   S6 = net across trades (grid)                   │
│  │ (reporting)          │   S6b = per-trade breakdown (portfolio detail)     │
│  │                      │                                                   │
│  │ - level (hr/day/mo)  │                                                   │
│  │ - period             │                                                   │
│  │ - peak/offpeak       │                                                   │
│  │ - net_mw / net_mwh   │                                                   │
│  │ - settled_value      │                                                   │
│  │ - forward_mark_value │                                                   │
│  │ (rebuildable)        │                                                   │
│  └──────────────────────┘                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 Volume Series Tables — In Detail

#### The "Header" Tables (one row per series version)

Think of these as the **cover page** of a document — metadata about the whole series:

| Table | What it represents | Owned by | series_type | Key fields |
|---|---|---|---|---|
| `volume_series` | The unified volume source for any trade | **Asset** (FORECAST) or **Trade-leg** (PROFILE) | FORECAST or PROFILE | series_type, asset_id/trade_leg_id, granularity, volume_unit, quality |
| `metered_actual_volume_series` | "This asset's meter measured X MW" | **Asset** (always) | N/A | asset_id, metering_point, rated_capacity_mw, quality |

**Two kinds of VolumeSeries in one table:**
- `series_type=FORECAST`: per asset (shared), weather-model-sourced, frequently updated
- `series_type=PROFILE`: per trade-leg (dedicated), created once at trade capture, few intervals, immutable

**Volume layer:** The `volume_layer` enum (VOLUME or METERED_ACTUAL) distinguishes which layer a supersession event refers to — VOLUME covers both FORECAST and PROFILE series, while METERED_ACTUAL covers meter data. This is used in event payloads (`VolumeSuperseded`, `VolumePublished`) and in the `compaction_view` table.

#### The Link Table: `volume_reference` (THE most important table — ALL trades have one)

**Every trade goes through this table.** It's the universal link between a trade and its volume:

| Field | PPA Example | DA Example | Meaning |
|---|---|---|---|
| `trade_leg_id` | T-7788-LEG-1 | T-5500-LEG-1 | Which trade (ALWAYS set) |
| `asset_id` | WP-NORDSEE | null | Which asset (null for fixed-profile) |
| `multiplier` | 0.30 | 1.0 | Trade's share of the volume |
| `effective_from` | 2026-08-01 | 2026-04-24 | When this allocation starts |
| `effective_to` | 2027-08-01 | 2026-04-25 | When this allocation ends |
| `volume_series_key` | FCST-WP-NORDSEE | VS-T5500-1 | Points to the VolumeSeries |
| `metered_series_key` | MTR-WP-NORDSEE | null | Points to meter (null for DA) |
| `formula` | (tolerance, seasonal adj) | (baseVolume: 50 MW) | Contract parameters |

**Why does `effective_from/to` live HERE instead of being derived from the trade?**

Five reasons:

1. **Stepped allocations**: A trade might have 20% in year 1 (ramp-up) then 30% from year 2. That's two rows with different periods and multipliers — you can't express this with a single date range from the trade.

2. **Partial unwinds**: If you sell back half your allocation for the last 6 months, the reference period shortens but the trade itself isn't amended.

3. **Sub-range coverage**: A trade starts Aug 2026, but the asset connects to the grid in Jan 2027. The allocation only starts in Jan — different from the trade's start.

4. **Query performance**: "Which trades are active on this asset in August 2027?" is answered with one indexed query on the reference table. No join to the trade table needed.

5. **Independent lifecycle**: Extending a trade's delivery period and extending the allocation are separate business decisions. Coupling them creates unnecessary coordination.

**Multiplier rules:**
- Each individual multiplier is between 0 and 1 (e.g., 0.30 = 30%)
- For asset-linked trades: the sum across all trades for one asset **within any time window** should ideally be 1.0 (100% allocated)
- Sum < 1.0 is fine (uncontracted capacity — the farm hasn't sold its full output yet)
- Sum > 1.0 triggers a warning (over-selling — a risk condition, but not blocked)
- For fixed-profile trades: multiplier is always 1.0 (100% — the trade owns its entire volume series)

#### The "Interval" Tables (many rows per series — the actual data)

These are the **bulk of the data**. Each row = one time slot:

| Table | Owned by | Fields | Example row |
|---|---|---|---|
| `volume_interval` (FORECAST) | **Asset** | series_id, start, end, volume, energy | series=def, 2026-08-01 00:00–00:15, **72.500000 MW** (full farm), 18.125000 MWh |
| `volume_interval` (PROFILE) | **Trade-leg** | series_id, start, end, volume, energy, status, chunk_month | series=abc, 2026-04-24 00:00–00:15, 50.000000 MW, 12.500000 MWh, CONFIRMED |
| `metered_actual_interval` | **Asset** | series_id, start, end, volume, energy | series=ghi, 2026-08-01 00:00–00:15, **68.300000 MW** (full farm), 17.075000 MWh |

**Key point:** FORECAST intervals store the **full asset output**, not a trade's share. The trade's share is calculated at read time by applying the multiplier:

```
Farm forecast for 18:45 = 72.5 MW (stored once)

Trade T-7788 reads it: 72.5 × 0.30 = 21.75 MW  (calculated, not stored)
Trade T-8899 reads it: 72.5 × 0.30 = 21.75 MW  (calculated, not stored)
Trade T-9900 reads it: 72.5 × 0.40 = 29.00 MW  (calculated, not stored)

DA Fill T-5500 reads its own PROFILE: 50.0 × 1.0 = 50.0 MW  (same code path!)
```

**Scale per table:** ~2,976 rows per FORECAST series per month (31 days x 96 slots). Asset-linked series are shared — one farm with 5 trades stores 2,976 rows, not 14,880. PROFILE series are tiny (a DA fill = ~96 rows total).

#### The VolumeFormula (the "Recipe")

Attached to the VolumeReference. Tells the system HOW to generate intervals for PROFILE series:

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
| `quantity_ref` | VS-3312 | **Denormalized pointer** to the volume_series_key (avoids join to volume_reference at query time). Same value as `volume_reference.volume_series_key` for this trade-leg. |
| `delivery_point` | DE_LU (Germany-Luxembourg zone) | Where on the grid |
| `portfolio` / `book` | "Renewables" / "Wind-DE" | Organizational attribution |
| `delivery_range` | [2026-08-01, 2026-09-01) | Which month this row covers |
| `price_expression_ref` | PXE-9001 | Points to the pricing formula |
| `valid_from` / `valid_to` | 2026-08-01 / open | "When was this true in the real world?" |
| `known_from` / `known_to` | 2026-07-15 14:32 / open | "When did the system learn this?" |

**Row count:** A full-year PPA = 12 rows (one per month) × ~2-5 versions = **24-60 rows total**. That's the entire legal record for a year-long contract. Extremely compact.

#### How Do I Query "Today's Position for My Portfolio"?

Two levels of detail depending on what you need:

**Level 1 — Nominal position (fast, one table, no joins):**

```sql
SELECT trade_id, leg_id, signed_quantity, volume_unit
FROM   position_ledger
WHERE  portfolio = 'Wind-DE'
  AND  delivery_point = 'DE_LU'
  AND  delivery_range @> '2026-07-20'
  AND  valid_to IS NULL          -- current version only
  AND  known_to IS NULL          -- not superseded
```

This gives you the headline MW per trade — the "dashboard view." No volume resolution needed.

**Level 2 — Per-interval position (e.g., "what's my MW at 14:00 today?"):**

Here you resolve actual volume through the series. Single query, three joins:

```sql
SELECT
    pl.trade_id,
    pl.leg_id,
    vs.series_type,                                    -- FORECAST or PROFILE
    vi.interval_start,
    vi.interval_end,
    vi.volume                      AS raw_mw,          -- full asset (FORECAST) or flat (PROFILE)
    vr.multiplier,
    vi.volume * vr.multiplier      AS resolved_mw,     -- trade's actual share
    vi.energy * vr.multiplier      AS resolved_mwh,
    SUM(vi.volume * vr.multiplier)
        OVER ()                    AS net_portfolio_mw  -- net across all trades
FROM   position_ledger pl
JOIN   volume_reference vr
       ON  vr.trade_leg_id = pl.leg_id
       AND '2026-07-21' BETWEEN vr.effective_from AND vr.effective_to
JOIN   volume_series vs
       ON  vs.series_key = vr.volume_series_key
       AND vs.quality_state IN ('CURRENT', 'EFFECTIVE')  -- latest version only
JOIN   volume_interval vi
       ON  vi.series_id  = vs.id
       AND vi.interval_start = '2026-07-21 14:00:00'     -- the specific slot
WHERE  pl.tenant_id      = 'TN_0042'
  AND  pl.portfolio      = 'Wind-DE'
  AND  pl.delivery_point = 'DE_LU'
  AND  pl.delivery_range @> '2026-07-21'::date
  AND  pl.valid_to  IS NULL                              -- current version
  AND  pl.known_to  IS NULL                              -- not superseded
ORDER BY pl.trade_id;
```

**What each join does:**

| Join | From → To | Purpose |
|---|---|---|
| `pl → vr` | position_ledger → volume_reference | Find the multiplier + series key for each trade-leg |
| `vr → vs` | volume_reference → volume_series | Resolve the series key to the actual series header |
| `vs → vi` | volume_series → volume_interval | Get the raw MW for the requested time slot |

**Example result set — portfolio "Wind-DE", zone DE_LU, at 14:00 today:**

| trade_id | series_type | raw_mw | multiplier | resolved_mw | net_portfolio_mw |
|---|---|---|---|---|---|
| T-7788 | FORECAST | 68.3 | 0.30 | **20.49** | 90.98 |
| T-8899 | FORECAST | 68.3 | 0.30 | **20.49** | 90.98 |
| T-5500 | PROFILE | 50.0 | 1.00 | **50.00** | 90.98 |

Note how T-7788 and T-8899 read the **same** volume_interval row (same asset forecast) — the multiplier is the only thing that differs.

**For a full day (all 96 intervals), change the interval filter:**

```sql
-- Replace the single-slot filter:
--   AND vi.interval_start = '2026-07-21 14:00:00'
-- With a range:
  AND vi.interval_start >= '2026-07-21 00:00:00'
  AND vi.interval_start <  '2026-07-22 00:00:00'
```

**For settlement — two separate queries by trade type (no mixing/fallback):**

```sql
-- PPA / asset-linked trades (meteredSeriesKey IS NOT NULL):
-- Replace the forecast join with metered actual:
JOIN   metered_actual_volume_series mas
       ON  mas.series_key = vr.metered_series_key       -- metered, not forecast
       AND mas.quality_state IN ('PROVISIONAL', 'VALIDATED', 'ESTIMATED')
JOIN   metered_actual_interval mai
       ON  mai.series_id = mas.id
       AND mai.interval_start = '2026-07-21 14:00:00'
WHERE  vr.metered_series_key IS NOT NULL                -- only asset-linked trades

-- DA / bilateral trades (meteredSeriesKey IS NULL by design):
-- Use the PROFILE volume_series directly (same query as the forward marks query above).
-- The traded volume IS the settled volume — this is the designed path, not a fallback.
WHERE  vr.metered_series_key IS NULL                    -- only fixed-profile trades
```

**Why `quantity_ref` helps but doesn't replace `volume_reference`:**
- `quantity_ref` is a denormalized shortcut to identify *which* volume series to read (saves the `pl → vr` join for series lookup)
- You still need `volume_reference` for the **multiplier** — so the join isn't fully avoided for interval-level queries
- For aggregate/dashboard queries, `signed_quantity` alone is sufficient (zero joins)

#### Settlement Cells (S5a) — Real Money, Permanent

One row per delivered time slot per trade. This is where most of the data lives:

| Field | Example | Meaning |
|---|---|---|
| `position_id` | POS-5501 | Which position block |
| `interval_start` | 2026-08-15 18:45 | Which 15-min slot |
| `value` | 777.48 (EUR) | Computed: price × volume for this slot (in settlement currency) |
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
| `mark_value` | 892.50 (EUR) | Current estimated value in settlement currency (curve × forecast) |

**Row count:** Only exists for undelivered slots in the "hot window" (next ~60 days). When a slot is delivered and settles, its forward mark is deleted and a settlement cell replaces it.

**Initial state:** When a new trade is booked, its positions appear in the slot cache immediately but carry `UNVALUED` forward marks until curve data is applied. This ensures the grid shows the position exists even before the first valuation run.

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

#### Trade Interval Cache (S6b) — Per-Trade Volume Breakdown (Optional)

Where slot_cache (S6) shows the **net** position across all trades, trade_interval_cache breaks it down **per trade**. This is for the portfolio-detail dashboard: "show me each trade's resolved MW at every interval."

| Field | Example | Meaning |
|---|---|---|
| `trade_leg_id` | T-7788-LEG-1 | Which trade |
| `interval_start` | 2026-08-15 18:45 | Time slot |
| `resolved_mw` | 20.49 | Pre-multiplied: raw volume × multiplier |
| `resolved_mwh` | 5.1225 | Pre-multiplied energy |
| `multiplier` | 0.30 | Stored for auditability (can verify: resolved ÷ multiplier = raw) |
| `series_key` | FCST-WP-NORDSEE | Which volume series was used |
| `version_hash` | 0x8B2C... | For staleness detection |

**Why this exists (and why it's optional):**
- **Without S6b:** To see per-trade volumes, you join `position_ledger → volume_reference → volume_interval` and apply the multiplier at query time. This works fine for a handful of trades but slows down when a portfolio dashboard needs to show 200 trades × 2,976 intervals = ~595K rows resolved on the fly.
- **With S6b:** Single-table scan. Pre-multiplied at write time. The dashboard query becomes:

```sql
SELECT trade_leg_id, interval_start, resolved_mw
FROM   trade_interval_cache
WHERE  trade_leg_id IN (... from position_ledger query ...)
  AND  interval_start >= '2026-08-15'
  AND  interval_start <  '2026-08-16'
ORDER BY trade_leg_id, interval_start
```

**Rebuild triggers (event-driven):**
- `VolumeSuperseded` → rebuild intervals for all trades referencing that volume series
- `VolumeReference` changed (multiplier update, new allocation) → rebuild that trade's intervals
- Trade amended → rebuild affected trade-leg's intervals

**Key properties:**
- **Not source of truth.** Entirely rebuildable from volume_reference + volume_interval.
- **Optional.** Systems that don't need per-trade dashboards can skip this table entirely.
- **Event-driven.** No batch jobs — rebuilt reactively when inputs change.
- **Staleness-safe.** `version_hash` lets the UI detect stale rows and show a "refreshing..." indicator.

**Row count:** `(P + T×Bf) × hot_months × D × I` = 5,000 × 2 × 30.4 × 96 = **~29M rows** in the hot window (2 months). Comparable to slot_cache but keyed per trade instead of per (zone, portfolio).

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
UNIFIED PATTERN — same for ALL trade types:

Asset (e.g., WindPark-Nordsee)
  │
  ├──→ VolumeSeries (type=FORECAST) ──→ VolumeIntervals (full asset output)
  │
  ├──→ MeteredActualVolumeSeries ──→ MeteredActualIntervals (full asset output)
  │
  └──→ VolumeReference(s): one per trade that slices this asset
           │
           │   multiplier = 0.30, volumeSeriesKey → FCST-WP-NORDSEE
           ▼
Trade T-7788 (PPA), Leg 1
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


DA Fill T-5500 (SAME pattern, just simpler):
  │
  ├──→ VolumeSeries (type=PROFILE) ──→ VolumeIntervals (96 intervals, 50 MW)
  │
  └──→ VolumeReference: multiplier = 1.0, volumeSeriesKey → VS-T5500-1
           │
           ▼
  └──→ Position Ledger rows → Settlement / Forward Marks / EOD
             uses: profile × 1.0 for volume (same code path!)
```

**Key insight:** Every trade resolves volume the same way: `VolumeReference` → `VolumeSeries` × `multiplier`. The only difference is whether the volume series is shared (asset forecast) or dedicated (trade profile), and whether the multiplier is fractional or 1.0.

### 9.5 What Is a "Source of Truth" vs "Derived/Rebuildable"

| Structure | Source of truth? | If we lose it... |
|---|---|---|
| Position Ledger | YES | We've lost the legal record. Unrecoverable. |
| PriceExpression | YES | We've lost the pricing recipes. Unrecoverable. |
| Volume Reference | YES | We've lost which trades link to which volume series and at what multiplier. Unrecoverable. |
| Volume Series (FORECAST + PROFILE) | YES | We've lost the volume data. Unrecoverable. |
| Metered Actual Series (per asset) | YES | We've lost the meter readings. Unrecoverable. |
| Settlement Cells | YES (derived but durable) | Must recompute from ledger + prices + volumes |
| EOD Struck Marks | YES (frozen snapshot) | Cannot be recreated after the business day passes |
| Forward Marks | NO (ephemeral) | Just recalculate from current curves. No loss. |
| Slot Cache (S6) | NO (rebuildable) | Rebuild from ledger. Temporary UI outage only. |
| Trade Interval Cache (S6b) | NO (optional, rebuildable) | Rebuild from volume_reference + volume_interval. Per-trade detail unavailable until rebuilt. |
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
| Application | Java 21 / Spring Boot 4.0.7 | Platform standard |

### 10.2 Partitioning Strategy (How Data Is Split)

All large tables are **partitioned by delivery month**. Think of it like filing cabinets organized by month:

```
volume_interval
  ├── volume_interval_2026_08  (all Aug 2026 delivery slots)
  ├── volume_interval_2026_09  (all Sep 2026 delivery slots)
  ├── volume_interval_2026_10  (all Oct 2026 delivery slots)
  └── ... (one partition per month)
```

**Why monthly?**
- Queries almost always ask "show me August data" — the database only looks in one partition
- Cleanup is easy: drop the whole partition after 7 years (instead of deleting rows one by one)
- New months are pre-created 6 months ahead (so we're never caught off guard)

**Tenant isolation:** Every query includes `tenant_id` as the first filter. The database uses this + delivery month to find data instantly without scanning everything.

### 10.3 Index Strategy (How We Find Data Fast)

| Index | On table | Purpose |
|---|---|---|
| `(series_id, interval_start)` | volume_interval, metered_actual_interval | "Give me slots for this series in time order" |
| `(series_key, version_id)` | volume_series, metered_actual_volume_series | "Give me a specific version of this series" |
| `(trade_leg_id)` | volume_reference | "What volume series does this trade use?" |
| `(asset_id)` | volume_reference | "Which trades reference this asset?" (fan-out) |
| `(tenant_id, delivery_range)` | Position ledger | "All positions for this customer in this month" |
| `(position_id, interval_start)` | Settlement cells | "All settled values for this position" |
| `(delivery_point, portfolio, interval)` | Slot cache | "Net position for this grid cell" |
| `(trade_leg_id, interval_start)` | Trade interval cache (S6b) | "Per-trade resolved volume in time order" |

### 10.4 Sizing — Real Numbers (with formulas)

**Starting assumptions (fleet-average tenant):**
```
T  = 200 tenants
A  = 8 assets (wind/solar farms) per tenant
N  = 2.5 trade-legs per asset (how many buyers share one farm)
P  = T × A × N = 200 × 8 × 2.5 = 4,000 PPAs total
Bf = 5 fixed-profile trades per tenant = 1,000 total
     → 25 trade-legs per average tenant (8×2.5 + 5)
     → Large tenants may have ~300 deals (see functional spec §15 worst-case sizing)
M  = 12 hot months
D  = 30.4 avg days per month
I  = 96 intervals per day (15-min)
Ih = 24 intervals per day (hourly)
```

**Formulas:**

| Table | Formula | Calculation | Result |
|---|---|---|---|
| **volume_interval** (FORECAST, per asset, hourly) | `T × A × M × D × Ih` | 200 × 8 × 12 × 30.4 × 24 | **~14M rows** |
| **volume_interval** (FORECAST, per asset, 15-min) | `T × A × M × D × I` | 200 × 8 × 12 × 30.4 × 96 | **~56M rows** |
| **volume_interval** (PROFILE, per trade, fixed) | `T × Bf × M × D × I` | 200 × 5 × 12 × 30.4 × 96 | **~35M rows** |
| **metered_actual_interval** (per asset, 6 delivered months) | `T × A × 6 × D × I` | 200 × 8 × 6 × 30.4 × 96 | **~28M rows** |
| **volume_reference** | `T × (A × N + Bf)` | 200 × (20 + 5) | **5,000 rows** |
| **Position ledger** | `(P + T×Bf) × M × versions` | 5,000 × 12 × ~2 | **~120K rows** |
| **Settlement cells** | `T × A × M_del × D × I × N × 1.3` | 200×8×6×30.4×96×2.5×1.3 | **~109M rows** |
| **Trade interval cache** (S6b, optional) | `(P + T×Bf) × hot_months × D × I` | 5,000 × 2 × 30.4 × 96 | **~29M rows** |
| **EOD struck marks** | `(P + T×Bf) × 12 × 21_biz_days` | 5,000 × 12 × 21 | **~1.3M rows** |

**The big wins from this model:**

| What was eliminated | Why | Rows saved |
|---|---|---|
| Separate contractual intervals for ALL PPAs | VolumeReference × multiplier replaces them | **~140M rows eliminated** (200×20×12×30.4×96) |
| Per-trade forecast copies | Stored once per asset, not per trade | **~21M rows saved** (vs old per-trade model) |
| Per-trade meter copies | Stored once per asset, not per trade | **~42M rows saved** |
| Separate code paths for different trade types | Unified resolution eliminates branching | **0 rows** but huge code simplicity win |

**Bottom line:** The "hot" volume data is ~77M–119M rows (depending on forecast granularity), down from ~245M+ in the old per-trade model. The position ledger is tiny (~120K rows). Settlement cells grow over time but only 6-12 months are in the "hot" partition.

**Why the savings are so dramatic for PPAs:** A 10-year PPA in the OLD model would store:
- 3,504,000 contractual intervals (10 years × 365 days × 96 intervals)

In the NEW model, that same PPA stores:
- **1 row** in `volume_reference` (multiplier + date range)
- 0 dedicated intervals (it reads the shared asset forecast/meter)

The forecast and meter data for that PPA's asset exists anyway (it's shared) — so the marginal cost of adding a PPA trade is literally 1 row.

**For DA trades:** A DA fill stores ~96 volume intervals (one day × 96 slots) in a dedicated PROFILE series, plus 1 row in `volume_reference` with multiplier=1.0. Same resolution path, tiny footprint.

### 10.5 Data Flow — The Life of a Number

Here's how a single electricity delivery flows through the system. Note how the asset stores data ONCE and multiple trades consume it:

```
STEP 0: Asset onboarded (before any trades)
  └─→ WindPark-Nordsee registered as asset (100 MW rated capacity)
  └─→ VolumeSeries (type=FORECAST) created (per asset — stores full farm output)
  └─→ MeteredActualVolumeSeries created (per asset — stores full farm output)

STEP 1: PPA Trade T-7788 is booked — buys 30% of asset (Day 0)
  └─→ Position Ledger: +30 MW for Aug 2026 (1 row)
  └─→ VolumeReference: (trade=T-7788, asset=WP-Nordsee, multiplier=0.30,
                         volumeSeriesKey=FCST-WP-NORDSEE, meteredSeriesKey=MTR-WP-NORDSEE)
  └─→ Slot Cache: 2,976 cells updated with forecast × 0.30

STEP 1b: PPA Trade T-8899 is booked — buys 30% of SAME asset
  └─→ Position Ledger: +30 MW for Aug 2026 (1 row)
  └─→ VolumeReference: (trade=T-8899, asset=WP-Nordsee, multiplier=0.30, ...)
  └─→ Same forecast data, different multiplier → different trade volumes

STEP 1c: DA Fill T-5500 is booked — 50 MW flat for Apr 24 (SAME pattern!)
  └─→ Position Ledger: +50 MW for Apr 2026 (1 row)
  └─→ VolumeSeries (type=PROFILE): 96 intervals of 50 MW created PER TRADE
  └─→ VolumeReference: (trade=T-5500, asset=null, multiplier=1.0,
                         volumeSeriesKey=VS-T5500-1, meteredSeriesKey=null)
  └─→ Same code path as PPAs — just multiplier=1.0 and dedicated series

STEP 2: Weather forecast arrives (Day 0 + hours)
  └─→ VolumeSeries (FORECAST): 2,976 intervals (e.g., 35-95 MW full farm output)
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

STEP 4b: DA Fill T-5500 settles (same day — exchange confirms volume)
  └─→ Settlement for T-5500: 96 cells (price × profile × 1.0)
  └─→ No metered series needed — the traded volume IS the settled volume

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
│  - Slot cache + trade interval cache populated (fast grid display)   │
│  - Forward marks live (constantly updating)                          │
│  - Event-driven updates active                                       │
│  - Redis-cached for sub-millisecond reads                            │
│  - This is what traders interact with                                │
│  - Reconciliation runs compare hot-window cache against source       │
├──────────────────────────────────────────────────────────────────────┤
│  WARM (delivered months within 12–14 month regulatory window)        │
│                                                                      │
│  - Settlement cells durable (audit/regulatory)                       │
│  - No slot cache (grid reads from rollups)                           │
│  - No forward marks (past = settled only)                            │
│  - Queried for audit, recon, disputes                                │
├──────────────────────────────────────────────────────────────────────┤
│  COLD (beyond regulatory window, within 7-year retention)            │
│                                                                      │
│  - Archived to S3/Glacier                                            │
│  - Derived layers (S5–S8) can be re-derived from archived S1/S2/S4  │
│  - Latency-relaxed access (minutes, not milliseconds)                │
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
| VolumeFormula (50 MW baseload, Berlin timezone, 15-min) | VolumeInterval rows in a PROFILE series |
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
| **VolumeReference** | The universal link between a trade and its volume series, carrying the multiplier (trade's share) |
| **Atomic interval** | The smallest time slot (usually 15 minutes in Germany) |
| **Bidding zone** | A region of the electricity grid where one price applies (e.g., Germany-Luxembourg) |
| **Bitemporal** | Tracking two timelines: "when was it true?" AND "when did we know it?" |
| **Cascade** | (V1.3 concept, removed in V3.0) Previously meant breaking a large block (e.g., yearly) into smaller ones. V3.0 stores intervals as-uploaded; no cascade tiers |
| **DST** | Daylight Saving Time — clocks changing twice a year |
| **Ephemeral** | Temporary; overwritten and not kept as history |
| **Fan-out** | Expanding one big interval into many small ones |
| **Forward mark** | An estimated future value based on current market curves |
| **Granularity** | The size of time slots (15-min, hourly, daily, monthly) |
| **Ledger** | The permanent record of all trading positions |
| **Multiplier** | A trade's share of a volume series (0.30 = 30% for PPAs; 1.0 = 100% for DA/bilateral); applied at read time |
| **PROFILE** | A VolumeSeries type for fixed-profile trades (DA, bilateral) — created per trade with known MW intervals |
| **Degenerate case** | A special case that's simpler but uses the same general structure (like a fixed price as a one-constant formula) |
| **MW** | Megawatt — a rate of power (like speed: km/h) |
| **MWh** | Megawatt-hour — an amount of energy (like distance: km) |
| **Netting** | Adding up buys and sells to get the net position |
| **PPA** | Power Purchase Agreement — a long-term renewable energy contract |
| **PriceExpression** | A formula tree that computes the price for each interval. Can be as simple as a single constant (fixed price) or as complex as a collar + CPI escalation + negative-price gate with multiple market data references. Fixed price = degenerate expression (one leaf, zero operators). See §4.2. |
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

## 13. Beyond Power — How This Design Extends to Other Commodities

The system is built for electricity today, but the core architecture works for **any commodity where multiple buyers or participants share a physical asset**. The `VolumeReference × multiplier` pattern doesn't know or care that it's slicing a wind farm — it just multiplies a volume by a fraction. Here's how the same pattern works for gas, oil, and agricultural commodities.

### 13.1 The Universal Pattern

```
Shared physical thing (asset/facility/pool/contract)
  │
  ├── VolumeSeries: the total output/capacity/allocation
  │
  └── VolumeReference(s): one per participant, each with a multiplier
         │
         └── resolved_volume = series_interval.volume × multiplier
```

**This is identical to power.** The only things that change are the units, the time intervals, and the pricing formulas.

### 13.2 Gas — Storage Withdrawal Rights

```
Storage facility "Rehden" (4,400 GWh working gas volume)
  │
  ├── VolumeSeries (type=FORECAST): hourly withdrawal/injection capacity forecast
  │     2026-10-15 06:00–07:00 → 180 GWh/h (full facility)
  │     2026-10-15 07:00–08:00 → 175 GWh/h
  │     ... (gas-day runs 06:00–06:00, not midnight–midnight)
  │
  └── VolumeReference(s):
         Trader A: 25% of facility → multiplier = 0.25
         Trader B: 15% of facility → multiplier = 0.15
         Trader C: 10% of facility → multiplier = 0.10
```

**Resolution (same code as power):**
```
Facility forecast for 08:00 = 175 GWh/h

Trader A reads it: 175 × 0.25 = 43.75 GWh/h  (their withdrawal right)
Trader B reads it: 175 × 0.15 = 26.25 GWh/h
Trader C reads it: 175 × 0.10 = 17.50 GWh/h

Same formula, same code path, same S6b cache — just different units.
```

**What changes vs power:**
| What | Power | Gas |
|---|---|---|
| Time intervals | 15-min slots, midnight–midnight | Hourly or gas-day (06:00–06:00 CET) |
| Units | MW / MWh | kWh/h / kWh (or therms) |
| Delivery point | Bidding zone (DE_LU) | Virtual trading point (THE, TTF) |
| Pricing | Collar + neg-price gate + HICP escalation | Basis differential + seasonal swing + take-or-pay floor |

**What stays the same:** VolumeReference, multiplier, VolumeSeries, S6b cache, settlement cells, dependency index, position ledger, bitemporal audit — all unchanged.

### 13.3 Oil — Consortium Lifting

```
Term contract "Bonny-Light-2027" (120,000 bbl/day for 12 months)
  │
  ├── VolumeSeries (type=PROFILE): monthly lifting schedule
  │     Jan 2027 → 120,000 bbl/day
  │     Feb 2027 → 115,000 bbl/day (planned maintenance)
  │     ... (one interval per month or per cargo)
  │
  └── VolumeReference(s):
         Participant A: 40% of contract → multiplier = 0.40
         Participant B: 35%              → multiplier = 0.35
         Participant C: 25%              → multiplier = 0.25
```

**Resolution (same code as power):**
```
Jan 2027 schedule = 120,000 bbl/day

Participant A: 120,000 × 0.40 = 48,000 bbl/day  (their lifting entitlement)
Participant B: 120,000 × 0.35 = 42,000 bbl/day
Participant C: 120,000 × 0.25 = 30,000 bbl/day
```

**What changes vs power:**
| What | Power | Oil |
|---|---|---|
| Time intervals | 15-min slots (35,000/year) | Monthly cargo windows (12/year) — much fewer |
| Units | MW / MWh | bbl/day / bbl (or MT) |
| Delivery point | Bidding zone (DE_LU) | Loading terminal (Bonny, Forcados) |
| Pricing | DA auction + collar | Dated Brent + quality differential + freight + demurrage |

### 13.4 Agriculture — Cooperative Pool

```
Cooperative contract "Wheat-Export-Q4-2027" (10,000 MT total)
  │
  ├── VolumeSeries (type=PROFILE): crop-month delivery allocation
  │     Oct 2027 → 4,000 MT
  │     Nov 2027 → 3,500 MT
  │     Dec 2027 → 2,500 MT
  │
  └── VolumeReference(s):
         Farm A: 15% of pool → multiplier = 0.15
         Farm B: 20% of pool → multiplier = 0.20
         Farm C: 30% of pool → multiplier = 0.30
```

**Resolution (same code as power):**
```
Oct 2027 allocation = 4,000 MT

Farm A: 4,000 × 0.15 = 600 MT  (their delivery obligation)
Farm B: 4,000 × 0.20 = 800 MT
Farm C: 4,000 × 0.30 = 1,200 MT
```

**What changes vs power:**
| What | Power | Ags |
|---|---|---|
| Time intervals | 15-min slots | Crop-month delivery windows |
| Units | MW / MWh | MT/period / MT (or bushels) |
| Delivery point | Bidding zone (DE_LU) | Warehouse / silo / port |
| Pricing | DA auction + collar | CBOT/Euronext front-month + basis + grade premium/discount |

### 13.5 What This Means for the Platform

```
┌─────────────────────────────────────────────────────────────────┐
│                    COMMODITY-NEUTRAL CORE                       │
│                    (unchanged across commodities)               │
│                                                                 │
│  Position Ledger (S1)      — same bitemporal, signed-qty model  │
│  PriceExpression (S2)      — same tree structure, new operators │
│  VolumeReference           — same multiplier × series pattern   │
│  VolumeSeries              — same FORECAST/PROFILE distinction  │
│  Settlement cells (S5a)    — same durable bitemporal measures   │
│  Forward marks (S5b)       — same ephemeral current-state       │
│  EOD struck marks (S5c)    — same frozen snapshots              │
│  Slot Cache (S6)           — same netted materialization        │
│  Trade Interval Cache(S6b) — same pre-multiplied per-trade cach │
│  Dependency index (S8)     — same blast-radius optimization     │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│                    COMMODITY PLUG-INS                           │
│                    (added per commodity, no core changes)       │
│                                                                 │
│  Power:  15-min grid, MW/MWh, peak/offpeak, cascade, DST        │
│  Gas:    Gas-day calendar (06:00), kWh/h, swing/ToP, VTP        │
│  Oil:    Monthly cargoes, bbl/day, quality diff, terminal       │
│  Ags:    Crop-month windows, MT, grade premium, warehouse       │
└─────────────────────────────────────────────────────────────────┘
```

**The key insight:** We built a power trading system, but what we actually built is a **shared-asset-slicing engine** that happens to be configured for power. The `volume × multiplier` pattern is the same whether you're slicing a wind farm's output, a gas storage facility's withdrawal rights, an oil consortium's lifting entitlements, or a farming cooperative's export pool. Zero core code changes — just new units, intervals, and pricing operators.

---

## 14. One-Page Summary for Validation

Use this checklist to validate the design with team members:

**Volume model:**
- [ ] **Unified resolution pattern** — ALL trades go through VolumeReference → VolumeSeries × multiplier
- [ ] **PPAs: shared asset forecast** — multiplier < 1.0, volume series shared across trades
- [ ] **DA/bilateral: dedicated profile** — multiplier = 1.0, per-trade volume series (few intervals)
- [ ] **Same code path for all trades** — fixed-profile is a "degenerate case" of the unified model
- [ ] **Forecast and meter are per ASSET** — one wind farm = one forecast, one meter (not per trade)
- [ ] **Multiplier is applied at read time** — volume data stored once, trade share calculated on query
- [ ] **Sum of multipliers should be ~1.0** — over-allocation is flagged as a warning, not an error
- [ ] **Store data as-is** — no forced conversion to different time resolutions
- [ ] **Each series versioned independently** — forecast v6 doesn't affect meter v1

**Position & valuation:**
- [ ] **Position ledger is the legal record** — tracks who owes what, with full audit trail
- [ ] **Prices are formulas, not fixed numbers** — computed per interval using live market data
- [ ] **Three valuation types** — settlement (permanent), forward marks (temporary), EOD snapshots (permanent)
- [ ] **Forward marks are NOT stored permanently** — they're live estimates, overwritten constantly
- [ ] **Settlement uses metered data for PPAs** — meter × multiplier; for DA, settlement uses the same profile × 1.0
- [ ] **One asset event fans out to all trades** — forecast/meter update triggers revaluation for every trade referencing that asset

**Infrastructure:**
- [ ] **The system can always answer "what did we think on date X?"** — regulatory requirement
- [ ] **DST is handled correctly** — 23h/25h days produce correct energy
- [ ] **Aurora PostgreSQL 16** — no TimescaleDB
- [ ] **7-year retention then delete** — per REMIT/MiFID II/GDPR
