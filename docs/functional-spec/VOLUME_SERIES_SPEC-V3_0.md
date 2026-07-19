# Volume Series Domain Model — Specification V3.0

**Module:** `power-volume-series`
**Group:** `com.quickysoft.power`
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 3.0.0
**Date:** July 2026

---

## 1. Purpose & Change Log

### 1.1 Purpose

This specification defines the domain model for representing, materializing, and validating delivery volume series in EU physical power trading. V3.0 is a **breaking rewrite** of V2.1 that restructures the volume model to align with the position-valuation functional spec §7 interface contract (three independent layers consumed by reference) while preserving correct domain logic (DST handling, energy calculation, BigDecimal precision, VolumeFormula).

The core design principle remains **"store the recipe, not the meal"**: the VolumeFormula is the source of truth, and materialized intervals are derived, regenerable artifacts.

### 1.2 Change Log (V3.0 from V2.1 — BREAKING)

| Area | V2.1 | V3.0 | Rationale |
|---|---|---|---|
| Root aggregate | 1 `VolumeSeries` + `BucketType` enum | 3 independent root aggregates: `ContractualVolumeSeries`, `ForecastVolumeSeries`, `MeteredActualVolumeSeries` | Aligns with position-valuation §7 FR-051 (three independent layers); eliminates bucket-type coupling |
| Series ownership | All series per trade-leg | CONTRACTUAL per trade-leg; FORECAST and METERED_ACTUAL **per asset** — shared across trade-legs via multiplier | An asset (e.g., wind farm) has one forecast and one meter; multiple trades slice it via capacity multipliers |
| Cascade tiers | `CascadeTier` (NEAR/MED/LONG) + disaggregation cron | **Removed**; store-as-uploaded + optional user-initiated compaction | User requirement: no forced cascade coarsening; store at upload granularity |
| PLAN bucket | Derived from CONTRACTUAL via disaggregation | **Removed entirely** | PLAN was a derived cascade artifact; without cascade, it has no source; valuation consumes FORECAST for undelivered intervals (FR-051a) |
| Wide-table scalars | 14+ scalar columns on VolumeInterval | **Removed**; each series type has only its relevant fields | Scalars were cascade-specific (RATE/ABSOLUTE classification for disaggregation); without cascade, they are unnecessary coupling |
| Storage engine | TimescaleDB compression | Aurora PG 16 + pg_partman + pg_cron | Platform constraint; Aurora is the target |
| Version model | Single version clock per VolumeSeries | Independent version clock per (series_key, layer) | Aligns with FR-055/055a — each layer is independently versioned |
| Granularity | `effectiveGranularity` per interval (mixed in single series) | One uniform granularity per series (CONTRACTUAL/FORECAST); always base for METERED_ACTUAL | Store-as-uploaded; no forced coarsening |
| Consumption contract | Internal methods (getContractualIntervals, etc.) | External query interface aligned with FR-054; multiplier applied at consumption time | Position/valuation consumes by reference, not by reaching into internals |

### 1.3 What Is Preserved from V2.1

- VolumeFormula and its sub-entities (ShapingEntry, SeasonalAdjustment) — unchanged
- TimeGranularity enum — unchanged
- VolumeUnit enum (MW_CAPACITY / MWH_PER_PERIOD) — unchanged
- ProfileType enum — unchanged
- DST handling rules (§5 verbatim) — unchanged
- Energy calculation logic (Instant-based arithmetic, BigDecimal scale 6) — unchanged
- Rolling horizon materialization for long-tenor CONTRACTUAL series — preserved
- Chunk processing pattern (Kafka message per month) — preserved
- Energy conservation invariant — preserved

---

## 2. Domain Context

### 2.1 EU Physical Power Market Characteristics

EU physical power markets operate on delivery intervals that vary by market and product type. The German bidding zone (DE-LU) uses 15-minute intervals for intraday continuous trading (EPEX SPOT / XBID), while day-ahead auctions clear on hourly or 15-minute products depending on the exchange. Bilateral OTC contracts and PPAs may use 30-minute, hourly, daily, or monthly granularity.

All delivery times are expressed in the delivery timezone (typically `Europe/Berlin` for CET/CEST), not UTC. This is critical because:

- CET/CEST observes two DST transitions per year
- The October fall-back creates a 25-hour day (02:00–03:00 occurs twice)
- The March spring-forward creates a 23-hour day (02:00–03:00 is skipped)
- Energy settlement is based on actual elapsed time, not nominal hours

### 2.2 Product Types

| Product | Typical Granularity | Typical Tenor | Materialization |
|---|---|---|---|
| DA Auction (EPEX SPOT) | 15 min | Single day | Full, immediate |
| Intraday Continuous | 15 min | Single interval to hours | Full, immediate |
| Short Block | 30 min / 1 hr | 1–6 hours | Full, immediate |
| Monthly Forward | Monthly | 1 month | Full, immediate |
| Annual Baseload | Monthly / Hourly | 1 year | Rolling horizon |
| PPA (Solar/Wind) | 15 min | 3–15 years | Rolling horizon |

### 2.3 Asset-Centric Volume Model

FORECAST and METERED_ACTUAL series are defined **per asset** (e.g., per wind farm, solar park, or metering point), not per trade. An asset has one physical output — one forecast, one meter. Multiple trades can reference the same asset's volume series, each with a **capacity multiplier** representing the trade's share of the asset's total output.

```
Asset "WindPark-Nordsee" (100 MW rated)
│
├── ForecastVolumeSeries (one per asset)         ← Weather model feeds
│     └── ForecastIntervals (the asset's full expected output)
│
├── MeteredActualVolumeSeries (one per asset)    ← TSO meter data
│     └── MeteredActualIntervals (the asset's full metered output)
│
├── Trade T-7788 LEG-1  (multiplier: 0.30)  → "This trade gets 30% of the asset"
│     └── ContractualVolumeSeries (per trade-leg) ← Trade capture
│
├── Trade T-8899 LEG-1  (multiplier: 0.30)  → "This trade gets 30%"
│     └── ContractualVolumeSeries (per trade-leg)
│
└── Trade T-9900 LEG-1  (multiplier: 0.40)  → "This trade gets 40%"
      └── ContractualVolumeSeries (per trade-leg)
                                              Total: 1.00 (fully allocated)
```

### 2.4 Position in the Trade Capture Pipeline

```
Asset Onboarding ──→ ForecastVolumeSeries (per asset)
                     MeteredActualVolumeSeries (per asset)

Trade Capture ─→ trade.captured ─→ Detail Generation Service
                                         │
                                         ▼
                              ContractualVolumeSeries (per trade-leg)
                              + AssetVolumeReference (links trade to asset
                                with multiplier for FORECAST/METERED_ACTUAL)
                                         │
                                         ▼
                              Position / Valuation Service
                              (reads asset series × multiplier)
```

The three volume layers have different ownership:
- **CONTRACTUAL**: per trade-leg, populated from trade capture (Detail Generation Service)
- **FORECAST**: per asset, populated from asset management / weather model feeds; shared across trades
- **METERED_ACTUAL**: per asset, populated from TSO metering data; shared across trades

---

## 3. Domain Model

### 3.1 Entity Relationships

```
Asset (reference entity, external)
├── ForecastVolumeSeries (root aggregate, per asset)
│   └── ForecastInterval (0:N, forecast volume intervals)
├── MeteredActualVolumeSeries (root aggregate, per asset)
│   └── MeteredActualInterval (0:N, metered delivery intervals)
└── AssetVolumeReference (0:N, links trade-legs to this asset)
    ├── multiplier (trade-leg's share of asset capacity, e.g., 0.30)
    └── tradeLegId (FK to trade leg)

ContractualVolumeSeries (root aggregate, per trade-leg)
├── VolumeFormula (1:1, the "recipe")
│   ├── ShapingEntry (0:N, time-of-use blocks)
│   └── SeasonalAdjustment (0:N, year/season modifiers)
└── ContractualInterval (0:N, materialized delivery intervals)

CompactionView (read model, optional)
└── CompactedInterval (0:N, coarsened intervals for display)
```

**Ownership summary:**

| Aggregate | Keyed by | Count per asset | Count per trade-leg |
|---|---|---|---|
| ForecastVolumeSeries | assetId | 1 (versioned) | 0 (referenced via AssetVolumeReference) |
| MeteredActualVolumeSeries | assetId | 1 (versioned) | 0 (referenced via AssetVolumeReference) |
| ContractualVolumeSeries | tradeLegId | 0 | 1 (versioned) |
| AssetVolumeReference | (assetId, tradeLegId) | N (one per trade that slices the asset) | 1 (this trade's share) |

### 3.2 Enumerations

#### 3.2.1 TimeGranularity (preserved from V2.1)

| Value | Duration | Fixed? | Notes |
|---|---|---|---|
| `MIN_5` | 5 minutes | Yes | Used in some intraday markets |
| `MIN_15` | 15 minutes | Yes | EPEX SPOT standard, XBID |
| `MIN_30` | 30 minutes | Yes | UK market (ELEXON), some OTC |
| `HOURLY` | 60 minutes | Yes | DA auction, bilateral OTC |
| `DAILY` | Variable | No | Settlement period granularity |
| `MONTHLY` | Variable | No | Forward contracts, PPAs |

`DAILY` and `MONTHLY` have variable duration. `isFixedDuration()` returns `false` for both. `getFixedDuration()` throws `UnsupportedOperationException`. `isSubDaily()` returns `true` for `MIN_5` through `HOURLY`.

#### 3.2.2 VolumeUnit (preserved from V2.1)

| Value | Meaning | Energy Derivation |
|---|---|---|
| `MW_CAPACITY` | Volume represents power capacity in MW | `energy = volume × elapsed hours` |
| `MWH_PER_PERIOD` | Volume represents energy delivered per period in MWh | `energy = volume` |

#### 3.2.3 ProfileType (preserved from V2.1)

| Value | Description |
|---|---|
| `BASELOAD` | Flat volume 24/7 |
| `PEAKLOAD` | Mon–Fri 08:00–20:00 (market-specific) |
| `OFFPEAK` | Inverse of peakload |
| `SHAPED` | Custom volume per time-of-use block |
| `BLOCK` | Named block product |
| `GENERATION_FOLLOWING` | Linked to renewable forecast |

#### 3.2.4 VolumeLayer (NEW)

Identifies which independent volume series a data point belongs to. Maps directly to position-valuation §7 FR-051 layers.

| Value | Series Aggregate | Keyed by | Mutability | Source |
|---|---|---|---|---|
| `CONTRACTUAL` | `ContractualVolumeSeries` | Trade-leg | Immutable after trade capture | Trade capture + materialization |
| `FORECAST` | `ForecastVolumeSeries` | Asset | Mutable (superseded on re-forecast) | Asset management, weather models |
| `METERED_ACTUAL` | `MeteredActualVolumeSeries` | Asset | Append-only; superseded on validation | TSO metering data |

FORECAST and METERED_ACTUAL volumes represent the **full asset output**. A trade-leg's share is determined by the `multiplier` on its `AssetVolumeReference` (see §3.3.9).

#### 3.2.5 QualityState (NEW)

Quality progression state per layer. Aligns with FR-054 `quality_state` field definition.

| Value | Applicable Layer | Meaning |
|---|---|---|
| `EFFECTIVE` | CONTRACTUAL | Trade is booked; intervals represent active obligation |
| `AMENDED` | CONTRACTUAL | Superseded by trade amendment (historical version) |
| `CURRENT` | FORECAST | Latest forecast version for the interval range |
| `SUPERSEDED` | FORECAST | Replaced by a newer forecast |
| `PROVISIONAL` | METERED_ACTUAL | Initial meter read (D+1 typically) |
| `VALIDATED` | METERED_ACTUAL | Confirmed by TSO/settlement body |
| `ESTIMATED` | METERED_ACTUAL | Gap-filled estimate pending actual read |

#### 3.2.6 MaterializationStatus (preserved from V2.1)

| Value | Meaning |
|---|---|
| `PENDING` | Not yet generated |
| `PARTIAL` | Rolling horizon — near-term materialized, far-dated pending |
| `FULL` | All intervals generated |
| `FAILED` | Generation failed, awaiting DLQ retry |

#### 3.2.7 IntervalStatus (preserved from V2.1)

| Value | Meaning |
|---|---|
| `CONFIRMED` | Exchange-confirmed or bilateral agreed |
| `ESTIMATED` | Derived from formula, not yet confirmed |
| `PROVISIONAL` | Subject to reconciliation |
| `CANCELLED` | Amendment cancelled this interval |

### 3.3 Root Aggregates

#### 3.3.1 ContractualVolumeSeries

The contracted volume shape — what was traded. One per trade leg. Stores intervals at the **trade's contractual granularity** as-is; no forced cascade coarsening.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesKey` | `String` | Stable external key (e.g., `VS-3312`) — survives amendments |
| `tradeId` | `String` | FK to parent trade |
| `tradeLegId` | `String` | FK to parent trade leg |
| `tradeVersion` | `int` | Trade version (optimistic lock) |
| `versionId` | `long` | Monotonically increasing version per (seriesKey, CONTRACTUAL) |
| `volumeUnit` | `VolumeUnit` | MW_CAPACITY or MWH_PER_PERIOD |
| `granularity` | `TimeGranularity` | Uniform interval width for this series |
| `deliveryStart` | `ZonedDateTime` | Start of delivery window (inclusive) |
| `deliveryEnd` | `ZonedDateTime` | End of delivery window (exclusive) |
| `deliveryTimezone` | `ZoneId` | Delivery timezone (e.g., `Europe/Berlin`) |
| `profileType` | `ProfileType` | Delivery profile classification |
| `qualityState` | `QualityState` | EFFECTIVE or AMENDED |
| `materializationStatus` | `MaterializationStatus` | Tracks rolling-horizon progress |
| `materializedThrough` | `YearMonth` | Last fully materialized month (null if FULL/PENDING) |
| `totalExpectedIntervals` | `int` | Pre-calculated for progress tracking |
| `materializedIntervalCount` | `int` | Current count of materialized intervals |
| `transactionTime` | `Instant` | Bi-temporal: when system recorded this version |
| `validTime` | `Instant` | Bi-temporal: when economically effective |
| `formula` | `VolumeFormula` | Contract-level volume definition (the "recipe") |
| `intervals` | `List<ContractualInterval>` | Ordered by `intervalStart` |

#### 3.3.2 ContractualInterval

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent ContractualVolumeSeries |
| `intervalStart` | `ZonedDateTime` | Start (inclusive), in delivery timezone |
| `intervalEnd` | `ZonedDateTime` | End (exclusive), in delivery timezone |
| `volume` | `BigDecimal` | Volume value; interpretation per parent's `volumeUnit` |
| `energy` | `BigDecimal` | Derived MWh (see energy calculation §5.5/V2.1 §3.3.2) |
| `status` | `IntervalStatus` | Lifecycle status |
| `chunkMonth` | `YearMonth` | Which materialization chunk produced this |

#### 3.3.3 ForecastVolumeSeries

Expected generation/consumption for undelivered intervals — the volume assumption behind forward marks (FR-051: "Expected generation/consumption for undelivered intervals"). Stored at **whatever granularity uploaded** by the forecast provider.

**Ownership: per asset, not per trade.** A wind farm has one forecast series representing its total expected output. Multiple trade-legs reference this series via `AssetVolumeReference` with a multiplier. Volumes in the intervals represent the **full asset capacity** — consumers apply the trade-leg's multiplier to derive the trade's share.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesKey` | `String` | Stable external key (e.g., `FCST-WP-NORDSEE`) |
| `assetId` | `String` | FK to parent asset (wind farm, solar park, etc.) |
| `versionId` | `long` | Monotonically increasing version per (seriesKey, FORECAST) |
| `volumeUnit` | `VolumeUnit` | Typically MW_CAPACITY |
| `granularity` | `TimeGranularity` | Upload granularity (e.g., MIN_15, HOURLY) |
| `deliveryStart` | `ZonedDateTime` | Start of forecast coverage (inclusive) |
| `deliveryEnd` | `ZonedDateTime` | End of forecast coverage (exclusive) |
| `deliveryTimezone` | `ZoneId` | Delivery timezone |
| `qualityState` | `QualityState` | CURRENT or SUPERSEDED |
| `forecastSourceId` | `String` | External forecast source reference (model ID, vendor) |
| `ratedCapacityMw` | `BigDecimal` | Asset's rated (nameplate) capacity in MW |
| `publishedAt` | `Instant` | When the forecast was published by the source |
| `transactionTime` | `Instant` | When system ingested this version |
| `intervals` | `List<ForecastInterval>` | Ordered by `intervalStart` |

#### 3.3.4 ForecastInterval

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent ForecastVolumeSeries |
| `intervalStart` | `ZonedDateTime` | Start (inclusive) |
| `intervalEnd` | `ZonedDateTime` | End (exclusive) |
| `volume` | `BigDecimal` | Forecast volume (MW or MWh per parent's volumeUnit) |
| `energy` | `BigDecimal` | Derived MWh |

#### 3.3.5 MeteredActualVolumeSeries

What was actually delivered — metered data from TSO. Always at market base granularity (e.g., MIN_15 for EPEX DE_LU). Progresses through provisional → validated states.

**Ownership: per asset, not per trade.** The physical meter sits on the asset (wind farm, solar park). One metered series represents the **full asset output**. Multiple trade-legs reference this series via `AssetVolumeReference` with a multiplier to derive their share.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesKey` | `String` | Stable external key (e.g., `MTR-WP-NORDSEE`) |
| `assetId` | `String` | FK to parent asset |
| `versionId` | `long` | Monotonically increasing version per (seriesKey, METERED_ACTUAL) |
| `volumeUnit` | `VolumeUnit` | Typically MW_CAPACITY |
| `granularity` | `TimeGranularity` | Always market base granularity (e.g., MIN_15) |
| `deliveryStart` | `ZonedDateTime` | Start of metered coverage (inclusive) |
| `deliveryEnd` | `ZonedDateTime` | End of metered coverage (exclusive) |
| `deliveryTimezone` | `ZoneId` | Delivery timezone |
| `qualityState` | `QualityState` | PROVISIONAL, VALIDATED, or ESTIMATED |
| `meteringPointId` | `String` | EIC-W or equivalent metering point reference |
| `ratedCapacityMw` | `BigDecimal` | Asset's rated (nameplate) capacity in MW |
| `receivedAt` | `Instant` | When TSO data was received |
| `transactionTime` | `Instant` | When system ingested this version |
| `intervals` | `List<MeteredActualInterval>` | Ordered by `intervalStart` |

#### 3.3.6 MeteredActualInterval

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent MeteredActualVolumeSeries |
| `intervalStart` | `ZonedDateTime` | Start (inclusive) |
| `intervalEnd` | `ZonedDateTime` | End (exclusive) |
| `volume` | `BigDecimal` | Metered volume (MW or MWh) |
| `energy` | `BigDecimal` | Derived MWh |

#### 3.3.7 CompactionView (Optional Read Model)

A precomputed coarsened view of any series, created on user request. Not a source of truth — always regenerable from the underlying series.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `sourceSeriesId` | `UUID` | FK to source series (any layer) |
| `sourceLayer` | `VolumeLayer` | Which layer was compacted |
| `targetGranularity` | `TimeGranularity` | Coarsened granularity |
| `createdAt` | `Instant` | When compaction was performed |
| `intervals` | `List<CompactedInterval>` | Coarsened intervals |

#### 3.3.8 CompactedInterval

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `viewId` | `UUID` | FK to parent CompactionView |
| `intervalStart` | `ZonedDateTime` | Start (inclusive) |
| `intervalEnd` | `ZonedDateTime` | End (exclusive) |
| `volume` | `BigDecimal` | Aggregated volume (energy-weighted average for MW_CAPACITY; sum for MWH_PER_PERIOD) |
| `energy` | `BigDecimal` | Sum of source interval energies |
| `sourceIntervalCount` | `int` | How many source intervals were compacted |

#### 3.3.9 AssetVolumeReference (NEW)

Links a trade-leg to an asset's forecast and metered-actual volume series, specifying the trade-leg's **capacity multiplier** — the fraction of the asset's total output that this trade covers.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `assetId` | `String` | FK to asset (wind farm, solar park, etc.) |
| `tradeLegId` | `String` | FK to trade leg |
| `tradeId` | `String` | FK to parent trade (denormalized for queries) |
| `multiplier` | `BigDecimal` | Trade-leg's share of asset capacity (e.g., 0.30 = 30%) |
| `effectiveFrom` | `ZonedDateTime` | When this allocation starts (usually trade delivery start) |
| `effectiveTo` | `ZonedDateTime` | When this allocation ends (usually trade delivery end) |
| `forecastSeriesKey` | `String` | FK to asset's ForecastVolumeSeries.seriesKey |
| `meteredSeriesKey` | `String` | FK to asset's MeteredActualVolumeSeries.seriesKey |

**Multiplier rules:**

- `multiplier` is a `BigDecimal` in the range `(0, 1]` for a single trade-leg.
- The sum of all multipliers across all active trade-legs for one asset should normally equal `1.0` (fully allocated), but this is a **business validation**, not a hard constraint. Over-allocation (sum > 1.0) is a risk condition to flag, not an error to prevent — it may represent intentional over-selling or staggered delivery schedules.
- Under-allocation (sum < 1.0) represents uncontracted capacity — normal for assets not yet fully sold.

**How the multiplier is applied:**

When the position/valuation layer reads FORECAST or METERED_ACTUAL for a trade-leg:

```
trade_leg_volume = asset_series_interval.volume × multiplier
trade_leg_energy = asset_series_interval.energy × multiplier
```

The multiplier is applied at **consumption time**, not at storage time. The stored forecast and metered intervals always represent the full asset output. This ensures:
1. A forecast update is stored once, not N times for N trades
2. A meter reading is stored once, not N times for N trades
3. Multiplier changes (trade amendments) don't require rewriting volume data
4. The asset's total output is always directly queryable without reverse-engineering from trade shares

**Worked example:**

```
Asset: WindPark-Nordsee, rated 100 MW
Forecast interval 2026-08-15 18:45: volume = 72.5 MW, energy = 18.125 MWh

Trade T-7788 LEG-1 (multiplier: 0.30):
  trade_volume = 72.5 × 0.30 = 21.75 MW
  trade_energy = 18.125 × 0.30 = 5.4375 MWh

Trade T-8899 LEG-1 (multiplier: 0.30):
  trade_volume = 72.5 × 0.30 = 21.75 MW
  trade_energy = 18.125 × 0.30 = 5.4375 MWh

Trade T-9900 LEG-1 (multiplier: 0.40):
  trade_volume = 72.5 × 0.40 = 29.00 MW
  trade_energy = 18.125 × 0.40 = 7.25 MWh

Sum: 21.75 + 21.75 + 29.00 = 72.50 MW ✓ (equals asset total)
```

**Non-asset trades (exchange, bilateral flat-block):**

Trades that do not reference an asset (DA fills, bilateral flat blocks, monthly forwards) have no `AssetVolumeReference`. Their contractual volume comes from the `ContractualVolumeSeries` with a fixed formula, and they have no FORECAST or METERED_ACTUAL series. The position ledger uses its fixed `quantity` for these deals (per FR-051a).

### 3.4 Preserved Sub-Entities

#### 3.4.1 VolumeFormula (unchanged from V2.1 §3.3.3)

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent ContractualVolumeSeries |
| `baseVolume` | `BigDecimal` | Base volume in MW (for flat profiles) |
| `minVolume` | `BigDecimal` | Tolerance band floor (MW) |
| `maxVolume` | `BigDecimal` | Tolerance band cap (MW) |
| `shapingEntries` | `List<ShapingEntry>` | Time-of-use volume blocks (null if baseload) |
| `forecastSourceId` | `String` | External generation forecast reference |
| `forecastMultiplier` | `BigDecimal` | Fraction of forecast (e.g., 0.9 = 90%) |
| `seasonalAdjustments` | `List<SeasonalAdjustment>` | Year/season volume modifiers |
| `calendarId` | `String` | Reference to holiday/trading calendar |

#### 3.4.2 ShapingEntry (unchanged from V2.1 §3.3.4)

| Field | Type | Description |
|---|---|---|
| `applicableDays` | `Set<DayOfWeek>` | Which days this block applies to |
| `blockStart` | `LocalTime` | Block start (inclusive) |
| `blockEnd` | `LocalTime` | Block end (exclusive) |
| `volume` | `BigDecimal` | Volume in MW for this block |
| `appliesToHolidays` | `boolean` | Whether block applies on public holidays |
| `validFromMonth` | `Month` | Seasonal start (null = all months) |
| `validToMonth` | `Month` | Seasonal end |

#### 3.4.3 SeasonalAdjustment (unchanged from V2.1 §3.3.5)

| Field | Type | Description |
|---|---|---|
| `fromMonth` | `Month` | Adjustment period start |
| `toMonth` | `Month` | Adjustment period end |
| `fromYear` | `Integer` | Year start (null = all years) |
| `toYear` | `Integer` | Year end |
| `multiplier` | `BigDecimal` | Multiplicative factor (e.g., 1.02 = +2%) |
| `absoluteAdj` | `BigDecimal` | Additive MW adjustment |

Application order: `adjustedVolume = (baseVolume × multiplier) + absoluteAdj`.

---

## 4. Materialization Strategy

### 4.1 Store-As-Uploaded Principle

Each layer stores intervals at the granularity provided by its data source:

| Layer | Granularity Rule | Example |
|---|---|---|
| CONTRACTUAL | Trade's contractual granularity, uniform across all intervals | Hourly bilateral → all intervals HOURLY; 15-min PPA → all intervals MIN_15 |
| FORECAST | Whatever the forecast provider uploads | Weather model outputs at HOURLY; some at MIN_15 |
| METERED_ACTUAL | Always market base granularity | EPEX DE_LU → always MIN_15 |

**No forced cascade coarsening.** A 10-year PPA at 15-min granularity is stored at 15-min. The rolling horizon (§4.2) limits how many intervals exist at any point in time; compaction (§4.4) is available as an optional optimization.

### 4.2 Rolling Horizon for Long-Tenor CONTRACTUAL Series

Preserved from V2.1 §4.2. For long-tenor contracts (PPAs, multi-year forwards):

- Materialize a rolling window: M+1 through M+3 (configurable)
- Set `materializationStatus = PARTIAL`, `materializedThrough = M+3`
- Emit `VolumePublished` with `scope = PARTIAL`
- Enqueue monthly chunk jobs for remaining months as separate Kafka messages
- A monthly cron job extends the materialization window as time progresses

Short-term trades (DA, intraday, short blocks) are fully materialized immediately.

### 4.3 Chunk Processing (preserved from V2.1 §4.3)

Each monthly chunk is a separate Kafka message (`trade.detail.chunk.requested`) containing `{tradeId, tradeLegId, monthStart, monthEnd}`. This provides:

- **Parallelism**: Multiple months can be generated concurrently
- **Partial failure isolation**: If month 37 fails, months 1–36 remain valid
- **Progress visibility**: `materializedIntervalCount / totalExpectedIntervals`
- **Independent retry**: Failed chunks go to a DLQ and can be retried independently

### 4.4 Optional Compaction (replaces V2.1 cascade)

Compaction is a **user-initiated** operation that creates a coarsened read model from an existing series. It does not modify the source intervals.

**When to compact:**
- Display optimization for far-dated grid views (monthly view of a 15-min PPA)
- Reporting aggregation (yearly energy summaries)

**Compaction rules:**
- Source intervals must be contiguous within the compaction window
- Target granularity must be coarser than source granularity
- Energy is summed (not averaged) — `compacted.energy = sum(source.energy)`
- Volume is energy-weighted average for MW_CAPACITY: `compacted.volume = compacted.energy / elapsed_hours`
- Volume is summed for MWH_PER_PERIOD: `compacted.volume = sum(source.volume)`
- CompactionView is regenerable and may be deleted/recreated at any time

**What compaction does NOT do:**
- Does not delete source intervals
- Does not modify the source series version
- Does not emit supersession events
- Does not affect position/valuation consumers (they always read source granularity)

### 4.5 Impact on Downstream Services

The Position Service consumes volume at the stored granularity:

| Scenario | Source | Consumer Responsibility |
|---|---|---|
| Materialized CONTRACTUAL intervals exist | Read ContractualVolumeSeries intervals | Consumer expands to atomic grid if needed (§7) |
| Unmaterialized far-dated window | Read VolumeFormula for aggregate exposure | Contract-level position (no interval detail) |
| FORECAST for forward marks | Read ForecastVolumeSeries intervals | Consumer expands to atomic grid if needed |
| METERED_ACTUAL for settlement | Read MeteredActualVolumeSeries intervals | Already at market base granularity |

---

## 5. DST Handling

*Preserved verbatim from V2.1 §5.*

### 5.1 Why DST Matters

EU power settlement is based on actual delivered energy (MWh), which depends on actual elapsed time. On DST transition days, the nominal 24-hour day becomes 23 or 25 hours. Failing to handle this correctly creates reconciliation breaks against exchange settlement data.

### 5.2 Fall-Back (October, Last Sunday)

Clocks go from 03:00 CEST back to 02:00 CET. The hour 02:00–03:00 occurs twice.

- A full day has **25 hours**
- At 15-min granularity: **100 intervals** (not 96)
- At hourly granularity: **25 intervals** (not 24)
- Baseload 15 MW for the full day: **375 MWh** (not 360)

`ZonedDateTime.plus(Duration.ofMinutes(15))` handles this correctly because it operates on the underlying `Instant` and resolves back to the delivery timezone.

### 5.3 Spring-Forward (March, Last Sunday)

Clocks go from 02:00 CET to 03:00 CEST. The hour 02:00–03:00 does not exist.

- A full day has **23 hours**
- At 15-min granularity: **92 intervals** (not 96)
- At hourly granularity: **23 intervals** (not 24)
- Baseload 15 MW for the full day: **345 MWh** (not 360)

### 5.4 Annual Net Effect

For a full-year delivery period, the DST effects cancel out (-1 hour in spring + 1 hour in fall = net zero). However, for partial-year contracts that span only one transition, the effect is real and must be reflected in interval counts and energy totals.

### 5.5 Implementation Rule

All duration and energy calculations must use `Instant`-based arithmetic:

```java
Duration.between(intervalStart.toInstant(), intervalEnd.toInstant())
```

Never use `Duration.between(intervalStart, intervalEnd)` on `ZonedDateTime` directly — this uses wall-clock time and produces wrong results across DST boundaries.

**Energy Calculation (MW_CAPACITY mode):**

```
seconds = Duration.between(intervalStart.toInstant(), intervalEnd.toInstant()).getSeconds()
hours = seconds / 3600 (high intermediate precision scale 20, HALF_UP)
energy = (volume × hours).setScale(6, HALF_UP)
```

**Energy Calculation (MWH_PER_PERIOD mode):**

```
energy = volume
```

---

## 6. Key Design Invariants

### 6.1 Energy Conservation Across Granularities

For any given delivery window and flat volume in `MW_CAPACITY` mode, the total energy must be identical regardless of the granularity used to decompose it:

```
totalEnergy(5-min) == totalEnergy(15-min) == totalEnergy(30-min) == totalEnergy(hourly)
```

This invariant does NOT hold for `MWH_PER_PERIOD` mode (by design — each interval delivers its full volume as MWh regardless of duration).

### 6.2 Contiguity

Materialized intervals within a single series must be contiguous and non-overlapping:

```
∀ series S with ordered intervals [i₀, i₁, ..., iₙ]:
  ∀ k: S.intervals[k].intervalEnd == S.intervals[k+1].intervalStart
  S.intervals[0].intervalStart == S.deliveryStart
  S.intervals[n].intervalEnd == S.deliveryEnd (or materializedEnd for PARTIAL)
```

No cross-tier contiguity rules exist (cascade tiers are removed). Each series is a single contiguous block at its uniform granularity.

### 6.3 Layer Independence

The three series aggregates are fully independent:

```
∀ operations on ContractualVolumeSeries:
  ForecastVolumeSeries and MeteredActualVolumeSeries are unaffected

∀ operations on ForecastVolumeSeries:
  ContractualVolumeSeries and MeteredActualVolumeSeries are unaffected

∀ operations on MeteredActualVolumeSeries:
  ContractualVolumeSeries and ForecastVolumeSeries are unaffected
```

No layer derives from another layer. No operation on one layer triggers writes to another. Each layer has its own version clock, lifecycle, and data source.

### 6.4 Version Monotonicity

Within a (seriesKey, layer) pair, `versionId` is strictly monotonically increasing:

```
∀ supersession events for (seriesKey, layer):
  new_version_id > old_version_id
```

Version IDs are opaque to consumers — used for equality comparison and input-version-set stamping only (per FR-055a).

### 6.5 Compaction Energy Invariant

If a CompactionView exists for a series, the total energy must equal the source:

```
sum(compactedInterval.energy for all intervals in CompactionView)
  == sum(sourceInterval.energy for all source intervals in compaction window)
```

This ensures compaction never creates or destroys energy — it only changes the interval width representation.

### 6.6 Multiplier Allocation Invariant (NEW)

For a given asset and overlapping time window, the sum of all active trade-leg multipliers should not exceed the asset's total capacity:

```
∀ asset A, ∀ time window W:
  sum(ref.multiplier for all AssetVolumeReference ref
      WHERE ref.assetId == A
      AND ref.effectiveFrom < W.end
      AND ref.effectiveTo > W.start)
  should ≤ 1.0
```

This is a **business validation** (flagged as a warning), not a hard constraint. Over-allocation may be intentional (staggered delivery schedules, merchant risk). Under-allocation (sum < 1.0) represents uncontracted capacity and is normal.

**Multiplier energy conservation:** The sum of trade-leg energies derived via multiplier must equal the asset's total energy when the asset is fully allocated:

```
If sum(multipliers) == 1.0:
  sum(asset_interval.energy × ref.multiplier for each ref) == asset_interval.energy
```

### 6.7 Formula Regenerability (preserved)

Any materialized ContractualInterval set must be exactly reproducible from the `VolumeFormula` + `TradingCalendar` + `granularity` + `deliveryTimezone`. This enables safe retry of failed chunks, re-materialization after amendment, and audit verification.

### 6.8 Interval Count Determinism (preserved)

For a given `(deliveryStart, deliveryEnd, granularity, deliveryTimezone)`, the expected interval count is deterministic and reproducible. `calculateExpectedIntervals()` must return the same value every time.

### 6.9 Bi-Temporal Completeness (preserved)

Every `ContractualVolumeSeries` must have both `transactionTime` and `validTime` set for REMIT regulatory reporting.

---

## 7. Consumption Contract

This section defines the interface contract between the Volume Series module and the Position/Valuation service. It aligns with position-valuation functional spec §7 FR-050 through FR-057a.

### 7.1 Query Interface

**FR-054 alignment.** The VolumeSeries module exposes volume data through a query interface that returns data at the **stored granularity**. The consumer (position/valuation) is responsible for expansion to atomic grid if needed.

**Query signatures (logical):**

```
-- For CONTRACTUAL (per trade-leg, no multiplier):
queryContractualVolume(seriesKey, intervalRange) → List<VolumeRecord>

-- For FORECAST and METERED_ACTUAL (per asset, multiplier applied):
queryAssetVolume(seriesKey, layer, intervalRange, multiplier) → List<VolumeRecord>

-- Convenience: resolve by trade-leg (looks up AssetVolumeReference internally):
queryVolumeForTradeLeg(tradeLegId, layer, intervalRange) → List<VolumeRecord>
```

The `queryVolumeForTradeLeg` method resolves the `AssetVolumeReference` for the trade-leg, retrieves the asset's FORECAST or METERED_ACTUAL series, and applies the `multiplier` before returning. For CONTRACTUAL, it reads the trade-leg's own `ContractualVolumeSeries` directly.

**VolumeRecord fields (per FR-054):**

| Field | Type | Description |
|---|---|---|
| `interval_start` | `ZonedDateTime` | Half-open interval start in market-local wall-clock |
| `interval_end` | `ZonedDateTime` | Half-open interval end |
| `volume_mw` | `BigDecimal` | Average MW for the interval (**after multiplier for FORECAST/METERED_ACTUAL**) |
| `volume_mwh` | `BigDecimal` | Energy for the interval (**after multiplier**) |
| `version_id` | `long` | Series-version identifier for this data point |
| `quality_state` | `QualityState` | Per FR-054: EFFECTIVE/AMENDED for CONTRACTUAL; CURRENT/SUPERSEDED for FORECAST; PROVISIONAL/VALIDATED/ESTIMATED for METERED_ACTUAL |
| `asset_id` | `String` (nullable) | Asset reference (null for CONTRACTUAL, populated for FORECAST/METERED_ACTUAL) |
| `multiplier_applied` | `BigDecimal` (nullable) | The multiplier used (null for CONTRACTUAL; for audit trail) |

### 7.2 Granularity Expansion Responsibility

**FR-054a alignment.** The stored granularity may differ from the consumer's atomic grid:

| Scenario | Stored Granularity | Consumer Grid | Responsibility |
|---|---|---|---|
| CONTRACTUAL hourly, position grid 15-min | HOURLY | MIN_15 | Consumer expands: 1 hourly → 4 × 15-min (same MW, energy/4) |
| CONTRACTUAL monthly, position grid 15-min | MONTHLY | MIN_15 | Consumer expands using VolumeFormula profile |
| FORECAST 15-min, position grid 15-min | MIN_15 | MIN_15 | No expansion needed |
| METERED_ACTUAL 15-min, settlement cells | MIN_15 | MIN_15 | No expansion needed (always at base) |

**Expansion rules for MW_CAPACITY:**
- MW value replicates to each sub-interval (intensive property)
- Energy divides proportionally by duration

**Expansion rules for MWH_PER_PERIOD:**
- Energy divides uniformly across sub-intervals (each gets `source.energy / N`)
- MW derived from energy: `mw = (energy / N) / sub_interval_hours`

### 7.3 Layer Resolution (per FR-051a)

The valuation layer determines which layer to read based on valuation type and delivery status:

| Context | Layer Read | Multiplier | Fallback |
|---|---|---|---|
| Delivered interval, settlement cell (S5a) | METERED_ACTUAL (asset) | × trade-leg multiplier | None — cell not created if meter unavailable |
| Undelivered interval, forward mark (S5b) | FORECAST (asset) | × trade-leg multiplier | Zero volume + `VOLUME_MISSING` flag |
| Fan-out for slot cache / grid display | CONTRACTUAL (trade-leg) | None (already trade-leg-scoped) | Ledger's fixed `quantity` for flat-block deals |

For FORECAST and METERED_ACTUAL, the consumer always resolves the trade-leg's `AssetVolumeReference` to determine which asset series to read and what multiplier to apply. The returned volumes represent the **trade-leg's share**, not the full asset.

**FR-051b: No automatic cross-layer fallback.** Missing data produces an explicit gap, never a substituted value from another layer.

### 7.4 Version Stamping (per FR-056)

Every valuation cell's input-version-set includes the volume `version_id` used in resolution, keyed by `(series_key, layer)`. This makes settlement cells reproducible.

---

## 8. Event Model

### 8.1 VolumePublished

Emitted when a new volume series version is created (first publication or after re-materialization).

| Field | Type | Description |
|---|---|---|
| `series_key` | `String` | e.g., `VS-3312` |
| `layer` | `VolumeLayer` | CONTRACTUAL / FORECAST / METERED_ACTUAL |
| `version_id` | `long` | New version identifier |
| `delivery_range` | `[start, end)` | Half-open delivery range covered |
| `granularity` | `TimeGranularity` | Interval width of published data |
| `quality_state` | `QualityState` | Initial quality state |
| `scope` | `String` | FULL or PARTIAL (for rolling-horizon) |
| `event_time` | `Instant` | Processing timestamp |

### 8.2 VolumeSuperseded (per FR-052a)

Emitted when a volume series version is superseded by a new version. This is the primary event consumed by the position/valuation layer for revaluation triggers.

| Field | Type | Description |
|---|---|---|
| `series_key` | `String` | e.g., `VS-3312` |
| `layer` | `VolumeLayer` | CONTRACTUAL / FORECAST / METERED_ACTUAL |
| `affected_range` | `[start, end)` | Half-open interval range whose volume changed |
| `old_version_id` | `long` (nullable) | Version being superseded (null for first publication) |
| `new_version_id` | `long` | New version identifier |
| `quality_state` | `QualityState` | New quality state (e.g., PROVISIONAL → VALIDATED) |
| `event_time` | `Instant` | Processing timestamp (becomes `known_from` on resulting valuation-cell versions) |

**Consumer behavior (per FR-052b):**

1. On **METERED_ACTUAL** supersession → look up all trade-legs referencing this asset (via `AssetVolumeReference`); re-resolve settlement cells (S5a) for each trade-leg's affected intervals via dependency index, applying each trade-leg's multiplier
2. On **FORECAST** supersession → look up all trade-legs referencing this asset; overwrite forward marks (S5b) for each trade-leg's affected intervals (ephemeral, no bitemporality), applying each trade-leg's multiplier
3. On **CONTRACTUAL** supersession → informational to volume consumers; position/valuation cascade is driven by the trade-amendment event (per FR-052b point 4)

**Fan-out on asset events:** Because FORECAST and METERED_ACTUAL are per asset, a single supersession event for one asset may trigger revaluation for **multiple trade-legs**. The dependency index must carry edges from (asset series, interval) to all trade-legs that reference the asset. This is the primary reason for the `AssetVolumeReference` lookup table.

### 8.3 VolumeChunkMaterialized

Emitted when a chunk of a PARTIAL series is materialized (rolling-horizon extension).

| Field | Type | Description |
|---|---|---|
| `series_key` | `String` | e.g., `VS-3312` |
| `layer` | `VolumeLayer` | Always CONTRACTUAL (only contractual uses rolling horizon) |
| `chunk_month` | `YearMonth` | Month that was materialized |
| `version_id` | `long` | Current series version |
| `interval_count` | `int` | Number of intervals in this chunk |
| `materialization_status` | `MaterializationStatus` | Updated status (PARTIAL or FULL if last chunk) |
| `event_time` | `Instant` | Processing timestamp |

---

## 9. Retention & Partitioning

### 9.1 Platform: Aurora PostgreSQL 16

The target platform is **Amazon Aurora PostgreSQL 16** (not TimescaleDB). Partitioning and scheduling are handled via `pg_partman` (partition management) and `pg_cron` (scheduled jobs).

### 9.2 Partitioning Strategy

All three interval tables are partitioned by **delivery month** (the month of `interval_start`):

```sql
-- Contractual intervals
CREATE TABLE contractual_interval (
    id UUID PRIMARY KEY,
    series_id UUID NOT NULL,
    interval_start TIMESTAMPTZ NOT NULL,
    interval_end TIMESTAMPTZ NOT NULL,
    volume NUMERIC NOT NULL,
    energy NUMERIC NOT NULL,
    status TEXT NOT NULL,
    chunk_month TEXT NOT NULL
) PARTITION BY RANGE (interval_start);

-- Similar for forecast_interval, metered_actual_interval
```

**pg_partman configuration:**
- Partition interval: 1 month
- Pre-create: 6 months ahead
- Retention: managed by pg_cron job (see §9.3)

### 9.3 Retention Policy

| Regulation | Period | Scope |
|---|---|---|
| REMIT | 5 years from transaction | Transaction records |
| MiFID II | 5–7 years | Transaction records |
| EMIR | 5 years from termination | Derivative contracts |
| GDPR | Delete after retention | Personal data |

**Binding ceiling: 7 years post-settlement finalization.** Partitions are dropped when all intervals in the partition exceed `settlement_finalized_at + 7 years + 6 months`.

**pg_cron retention job** (runs monthly):
```sql
-- Pseudocode: drop partitions older than retention threshold
SELECT pg_partman.drop_partition_id(
    p_parent_table := 'public.contractual_interval',
    p_retention := '90 months'  -- 7.5 years
);
```

### 9.4 Row Count Estimates (V3.0 model)

Because FORECAST and METERED_ACTUAL are per **asset** (not per trade), data is stored once per asset regardless of how many trades slice it. This is a significant storage efficiency vs the V2.1 model.

| Layer | Rows per entity per month | Keyed by | Notes |
|---|---|---|---|
| CONTRACTUAL (15-min PPA) | ~2,976 | Per trade-leg | 31 days × 96 intervals (stored as-is) |
| FORECAST (15-min or hourly) | ~2,976 or ~744 | **Per asset** | Stored once; shared by N trade-legs |
| METERED_ACTUAL (15-min) | ~2,976 | **Per asset** | Stored once; shared by N trade-legs |
| AssetVolumeReference | 1 | Per (asset, trade-leg) | Negligible — just multiplier metadata |

**Platform sizing (200 tenants, ~20 PPAs each referencing ~8 distinct assets per tenant):**
- CONTRACTUAL: ~143M rows in hot partitions (200 × 20 PPAs × 12 months × 2,976)
- FORECAST: ~28M rows (200 × 8 assets × 12 months × ~1,500 avg) — **not** 143M, because data is per asset not per trade
- METERED_ACTUAL: ~28M rows (200 × 8 assets × 12 delivered months × ~2,976) — same per-asset benefit
- AssetVolumeReference: ~32,000 rows (200 × 8 assets × ~20 trade-legs across assets) — negligible
- **Total hot:** ~200M–300M rows

Aurora PG 16 handles this volume with proper partitioning, indexing on `(series_id, interval_start)`, and connection pooling.

### 9.5 Index Strategy

```sql
-- Primary access pattern: query by series + time range
CREATE INDEX idx_contractual_series_time
    ON contractual_interval (series_id, interval_start);

CREATE INDEX idx_forecast_series_time
    ON forecast_interval (series_id, interval_start);

CREATE INDEX idx_metered_series_time
    ON metered_actual_interval (series_id, interval_start);

-- Version lookup (for consumption contract)
CREATE INDEX idx_contractual_series_version
    ON contractual_volume_series (series_key, version_id);

-- Asset volume reference: "which trade-legs reference this asset?"
-- Critical for fan-out on forecast/meter supersession events
CREATE INDEX idx_asset_vol_ref_asset
    ON asset_volume_reference (asset_id);

-- Asset volume reference: "which asset does this trade-leg reference?"
-- Used by queryVolumeForTradeLeg
CREATE INDEX idx_asset_vol_ref_trade_leg
    ON asset_volume_reference (trade_leg_id);
```

---

## 10. Test Specification

### 10.1 Test Structure

```
VolumeSeriesV3Test
├── ContractualVolumeSeriesTests
│   ├── SingleInterval (15-min block, both VolumeUnit modes)
│   ├── MultiInterval (1-hour at 15-min, contiguity, energy)
│   ├── OneYearPPA (full materialization, interval count, energy totals)
│   ├── DSTFallBack (Oct 25 2026: 100 intervals, 375 MWh at 15MW)
│   ├── DSTSpringForward (Mar 28 2027: 92 intervals, 345 MWh at 15MW)
│   ├── PartialMaterialization (M+3 rolling horizon)
│   ├── ChunkMaterialization (extends window, promotes to FULL)
│   ├── FormulaRegeneration (intervals reproducible from formula)
│   ├── Immutability (intervals unchanged after creation)
│   ├── VersionMonotonicity (versionId increases on amendment)
│   └── UniformGranularity (all intervals have series granularity)
│
├── ForecastVolumeSeriesTests
│   ├── UploadAtHourlyGranularity (stored as HOURLY, not coarsened)
│   ├── UploadAt15MinGranularity (stored as MIN_15)
│   ├── Supersession (new version supersedes old, versionId increases)
│   ├── QualityStateTransition (CURRENT → SUPERSEDED on new upload)
│   ├── PartialRangeSupersession (only affected intervals change)
│   └── IndependenceFromContractual (contractual changes don't affect forecast)
│
├── MeteredActualVolumeSeriesTests
│   ├── AlwaysAtBaseGranularity (rejects non-base uploads)
│   ├── ProvisionalToValidated (quality_state progression)
│   ├── SupersessionOnValidation (new version for validated data)
│   ├── IndependenceFromForecast (forecast changes don't affect meter)
│   └── AppendOnlySemantics (corrections create new versions, never update)
│
├── CompactionTests
│   ├── 15MinToHourly (energy sum preserved, MW averaged)
│   ├── HourlyToDaily (DST day produces correct compacted energy)
│   ├── DailyToMonthly (variable days handled correctly)
│   ├── CompactionEnergyInvariant (source energy == compacted energy)
│   ├── CompactionDoesNotModifySource (source intervals unchanged)
│   └── CompactionRegenerable (delete + recreate produces same result)
│
├── AssetMultiplierTests
│   ├── SingleTradeFullCapacity (multiplier=1.0, trade volume == asset volume)
│   ├── ThreeTradesSplitCapacity (0.3+0.3+0.4=1.0, sum equals asset)
│   ├── MultiplierAppliedToForecast (trade_mw = asset_mw × multiplier)
│   ├── MultiplierAppliedToMeter (trade_energy = asset_energy × multiplier)
│   ├── ForecastSupersessionFansOutToAllTrades (3 trades all revalued)
│   ├── MeterSupersessionFansOutToAllTrades (3 trades all resettled)
│   ├── UnderAllocation (sum < 1.0, no error, uncontracted capacity)
│   ├── OverAllocationFlagged (sum > 1.0, warning not error)
│   ├── MultiplierChangeDoesNotRewriteAssetData (asset intervals unchanged)
│   ├── NonAssetTradeHasNoReference (DA fill, no AssetVolumeReference)
│   └── QueryVolumeForTradeLegResolvesCorrectAsset
│
├── LayerIndependenceTests
│   ├── ContractualWriteDoesNotAffectForecast
│   ├── ForecastWriteDoesNotAffectContractual
│   ├── MeterWriteDoesNotAffectForecastOrContractual
│   ├── IndependentVersionClocks (each layer increments independently)
│   └── IndependentQualityStates (each layer tracks own state)
│
├── ConsumptionContractTests
│   ├── QueryReturnsStoredGranularity (no implicit expansion)
│   ├── VolumeRecordFieldCompleteness (all FR-054 fields present)
│   ├── VersionIdInResponse (usable for input-version-set)
│   ├── QualityStateInResponse (correct per layer)
│   └── RangeQueryFiltering (only intervals in requested range returned)
│
└── DSTTests (cross-cutting, all layers)
    ├── FallBackDayIntervalCount (100 at 15-min, 25 at hourly)
    ├── SpringForwardDayIntervalCount (92 at 15-min, 23 at hourly)
    ├── EnergyUsesInstantArithmetic (not wall-clock)
    ├── AnnualNetEffect (full-year energy equals 8760h × MW)
    └── ContiguityAcrossDSTBoundary (no gaps or overlaps)
```

### 10.2 Key Test Scenarios (preserved from V2.1, adapted to V3.0)

#### Single 15-min Interval

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:45 – 18:00 CET |
| Granularity | 15 min |
| Volume | 15 |
| Profile | BLOCK |

**Expected:**
- Exactly 1 ContractualInterval produced
- **MW_CAPACITY:** Energy = 15 × 0.25 = **3.75 MWh**
- **MWH_PER_PERIOD:** Energy = **15 MWh**

#### One-Year PPA

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:00 – 24 Apr 2027, 17:00 CET |
| Granularity | MIN_15 |
| Volume | 15 MW |
| Profile | BASELOAD |

**Expected:**
- ~35,040 ContractualIntervals (365 × 96, ± 4 for DST)
- Total energy: ~131,400 MWh (15 MW × 8,760h ± 15 MWh)
- DST fall-back (25 Oct 2026): 100 intervals, 375 MWh
- DST spring-forward (28 Mar 2027): 92 intervals, 345 MWh

#### Cross-Granularity Energy Invariant

| Test | VolumeUnit | Assertion |
|---|---|---|
| Energy conservation | MW_CAPACITY | 5-min, 15-min, 30-min, hourly all produce 15 MWh for 17:00–18:00 at volume=15 |
| MWH_PER_PERIOD semantics | MWH_PER_PERIOD | Total energy scales with interval count (4 × 15 = 60 at 15-min vs 2 × 15 = 30 at 30-min) |

---

## 11. Design Decisions & Rationale

### 11.1 Why Three Independent Series (Not One Series with Bucket Types)

**V2.1 problem:** A single `VolumeSeries` with `BucketType` enum created coupling between layers that have fundamentally different lifecycles, sources, granularities, and mutability semantics. CONTRACTUAL intervals are immutable and trade-sourced; FORECAST intervals are mutable and weather-model-sourced; METERED_ACTUAL intervals are append-only and TSO-sourced. Forcing them into one aggregate violated the "independent lifecycle = independent aggregate" DDD principle.

**V3.0 resolution:** Three root aggregates, each with its own version clock, persistence table, and event stream. This aligns with:
- FR-051: "three volume-series layers by reference. Each serves a distinct valuation purpose and no layer may be substituted for another"
- FR-055: "Each volume-series layer is independently versioned using a supersession model"
- The physical reality that these data arrive from different systems at different times

### 11.2 Why No PLAN Layer

**V2.1 design:** PLAN intervals were derived from CONTRACTUAL via cascade disaggregation (monthly → daily → 15-min). They existed to provide base-granularity volume for near-term operations.

**V3.0 removal:** With cascade removed (store-as-uploaded), there is no CONTRACTUAL source at coarser granularity to disaggregate from. The PLAN layer's purpose is now served by:
- CONTRACTUAL already at base granularity (if trade is 15-min)
- FORECAST for forward marks on undelivered intervals (per FR-051a)
- Expansion at consumption time for coarser contractual series (per §7.2)

The position-valuation spec §7 explicitly does NOT consume a PLAN layer — it consumes CONTRACTUAL, FORECAST, and METERED_ACTUAL only. PLAN was an internal volume-module optimization, not a consumer-facing layer.

### 11.3 Why Store-As-Uploaded (Not Cascade)

**V2.1 design:** Cascade coarsening (NEAR=base, MED=daily, LONG=monthly) reduced storage for far-dated intervals, with a disaggregation cron to derive finer intervals as delivery approached.

**V3.0 removal reasons:**
1. **User requirement:** "Store volumes at upload granularity — no forced cascade coarsening"
2. **Complexity cost:** Cascade introduced CascadeTier, ScalarClassification (RATE/ABSOLUTE), disaggregation cron, cross-tier boundary alignment, mixed-granularity contiguity rules — all removed in V3.0
3. **Rolling horizon suffices:** A 10-year PPA doesn't need all 3.5M intervals materialized upfront. Rolling horizon (§4.2) materializes M+3, extending monthly. Far-dated intervals are created only when needed.
4. **Optional compaction:** Users who want coarser views can request a CompactionView — but this is a read model, not the source of truth

### 11.4 Why Aurora PG 16 (Not TimescaleDB)

**Platform constraint:** The target infrastructure is Amazon Aurora PostgreSQL 16. TimescaleDB is not available on Aurora.

**Mitigation for lost TimescaleDB features:**
- **Compression** → Aurora's built-in TOAST + pg_partman partition dropping replaces chunk compression
- **Continuous aggregates** → CompactionView (§3.3.7) serves the same purpose for volume roll-ups
- **Retention policies** → pg_cron + pg_partman `drop_partition_id` (§9.3)
- **Hypertable auto-partitioning** → pg_partman range partitioning by delivery month

### 11.5 Why FORECAST and METERED_ACTUAL Are Per Asset (Not Per Trade)

**Domain truth:** A wind farm has one physical output. The weather doesn't know about trade contracts. The TSO meter reads the farm's total production, not each buyer's share. Storing forecast/meter data per trade would mean:
- N copies of the same data for N trades referencing the same asset
- A forecast update requires N writes instead of 1
- A meter correction requires N writes instead of 1
- No single-source view of the asset's actual performance

**V3.0 resolution:** FORECAST and METERED_ACTUAL are keyed by `assetId`. Trade-legs reference the asset via `AssetVolumeReference` with a `multiplier`. The multiplier is applied at consumption time (read path), not storage time (write path). This means:
1. One forecast update → one write → N revaluations (via fan-out)
2. One meter read → one write → N resettlements (via fan-out)
3. Multiplier changes (trade amendment, capacity reallocation) are metadata-only — no volume data rewrite
4. Asset-level analytics (total production, capacity factor) are direct queries with no reverse-engineering

**When trades DON'T reference an asset:** Exchange fills (DA, intraday), bilateral flat blocks, and monthly forwards have no asset. They have no `AssetVolumeReference`, no FORECAST, and no METERED_ACTUAL. Their volume comes from the `ContractualVolumeSeries` (fixed formula) and the position ledger's `quantity` field.

### 11.6 ZonedDateTime Over UTC (preserved from V2.1)

Delivery times stored in delivery timezone (`Europe/Berlin`), not UTC. Preserves trader's mental model and contractual delivery semantics. UTC conversion loses DST fall-back disambiguation.

### 11.7 BigDecimal Over double (preserved from V2.1)

All volume and energy values use `BigDecimal` with scale 6. Avoids floating-point drift across 35,000+ interval summations.

### 11.8 Price Excluded from Interval Entities (preserved from V2.1)

Price is owned by the Pricing Service. Keeping it separate avoids write amplification when price curves update.

---

## Appendix A: FR Cross-Reference

| Position-Valuation FR | V3.0 Coverage |
|---|---|
| FR-050 (boundary: positions vs volumes) | §2.3 pipeline diagram; three volume layers are operational, not position rows |
| FR-051 (three layers consumed) | §3.3 — three root aggregates map 1:1 to FR-051 layers |
| FR-051a (layer resolution rules) | §7.3 — layer resolution table |
| FR-051b (no cross-layer fallback) | §6.3 layer independence invariant; §7.3 explicit "None" fallback |
| FR-052 (react to supersession events) | §8.2 VolumeSuperseded event; consumer behavior defined |
| FR-052a (VolumeSuperseded event payload) | §8.2 — field-by-field match to FR-052a table |
| FR-053 (dual projection: risk + ops) | §6.3 layer independence — layers serve different consumers independently |
| FR-054 (data shape and grain) | §7.1 VolumeRecord fields; §7.2 granularity expansion |
| FR-054a (grain conversion is consumer responsibility) | §7.2 — explicit consumer responsibility table |
| FR-055 (independent versioning, supersession) | §3.3.1/3.3.3/3.3.5 `versionId` field; §6.4 version monotonicity |
| FR-055a (version identity, opaque to consumers) | §7.4 version stamping; §6.4 monotonicity rule |
| FR-055b (supersession triggers by layer) | §8.2 consumer behavior section |
| FR-056 (version in input-version-set) | §7.4 version stamping |
| FR-056a (flat-block deals, no volume version) | §7.3 — ledger's fixed `quantity` for flat-block deals |
| FR-057 (dependency index edges) | §8.2 consumer behavior — settlement cell and forward mark edges |
| FR-057a (active_leaves membership) | §8.2 — dependency index lookup for supersession handling |

---

## Appendix B: Migration Notes (V2.1 → V3.0)

### Removed Concepts (no V3.0 equivalent)

| V2.1 Concept | Disposition |
|---|---|
| `BucketType` enum | Replaced by `VolumeLayer` enum + separate aggregates |
| `CascadeTier` enum | Removed entirely |
| `ScalarClassification` enum | Removed (no disaggregation) |
| `ScalarColumn` registry | Removed (no wide-table scalars) |
| Wide-table scalar columns | Removed from interval entities |
| `effectiveGranularity` field | Removed (uniform per series) |
| `cascadeTier` field | Removed |
| `deriveDaily()` / `deriveBaseGranularity()` | Removed (no PLAN derivation) |
| `nearMidBoundary` / `midLongBoundary` | Removed (no cascade) |
| TimescaleDB compression/retention | Replaced by pg_partman + pg_cron |

### New Concepts (no V2.1 equivalent)

| V3.0 Concept | Purpose |
|---|---|
| `AssetVolumeReference` | Links trade-legs to shared asset forecast/meter series with capacity multiplier |
| `assetId` on FORECAST/METERED_ACTUAL | Series ownership is per asset, not per trade |
| `ratedCapacityMw` on asset series | Nameplate capacity for allocation validation |
| `multiplier_applied` on VolumeRecord | Audit trail of which multiplier was used at consumption time |

### Mapping for Existing Data

| V2.1 Row | V3.0 Destination |
|---|---|
| VolumeInterval with `bucketType=CONTRACTUAL` | `contractual_interval` table |
| VolumeInterval with `bucketType=PLAN` | **Discarded** (regenerable; no longer needed) |
| VolumeInterval with `bucketType=ACTUAL` | `metered_actual_interval` table (re-keyed to asset, volumes represent full asset output) |
| Forecast data (if stored externally) | `forecast_interval` table (re-keyed to asset) |
| Trade-to-forecast linkage (was `forecastSourceId` on VolumeFormula) | `AssetVolumeReference` with multiplier |
