# Volume Series Domain Model — Specification V3.0

**Module:** `power-volume-series`
**Group:** `com.quickysoft.power`
**Domain:** EU Physical Power Trade Capture (CTRM/ETRM)
**Version:** 3.0.0
**Date:** July 2026

---

## 1. Purpose & Change Log

### 1.1 Purpose

This specification defines the domain model for representing, materializing, and validating delivery volume series in EU physical power trading. V3.0 is a **breaking rewrite** of V2.1 that introduces a **unified volume model** where every trade follows ONE resolution pattern through a `VolumeReference` pointing to a `VolumeSeries` with a `multiplier`.

The core design principle remains **"store the recipe, not the meal"**: the VolumeFormula is the source of truth, and materialized intervals are derived, regenerable artifacts.

**Key insight:** A fixed-profile trade (DA, bilateral) is a **degenerate case** of the unified model — its `VolumeSeries(seriesType=PROFILE)` is created per-trade with the trade's MW profile, and the `VolumeReference` points to it with `multiplier=1.0`. This mirrors how a fixed price is a degenerate PriceExpression (D-2). One code path resolves all trades.

### 1.2 Change Log (V3.0 from V2.1 — BREAKING)

| Area | V2.1 | V3.0 | Rationale |
|---|---|---|---|
| Root aggregate | 1 `VolumeSeries` + `BucketType` enum | 2 independent root aggregates: `VolumeSeries` (unified) + `MeteredActualVolumeSeries` | Unified model: one resolution path for all trades; metered actuals remain separate (per-asset, different lifecycle) |
| Category distinction | Category A (asset-linked) vs Category B (fixed-profile) | **Eliminated** — all trades use `VolumeReference` → `VolumeSeries` × `multiplier` | One code path; fixed-profile is a degenerate case (multiplier=1.0, per-trade PROFILE series) |
| Series types | `ForecastVolumeSeries` + `ContractualVolumeSeries` | Unified `VolumeSeries` with `seriesType` enum (FORECAST / PROFILE) | Eliminates separate aggregates; same resolution logic for both |
| Interval types | `ForecastInterval` + `ContractualInterval` | Unified `VolumeInterval` | One table, one schema, one query path |
| Trade-to-volume link | `AssetVolumeReference` (Cat A only) + direct ownership (Cat B) | `VolumeReference` (universal for ALL trades) | Every trade resolves volume the same way |
| Cascade tiers | `CascadeTier` (NEAR/MED/LONG) + disaggregation cron | **Removed**; store-as-uploaded + optional user-initiated compaction | User requirement: no forced cascade coarsening; store at upload granularity |
| Storage engine | TimescaleDB compression | Aurora PG 16 + pg_partman + pg_cron | Platform constraint; Aurora is the target |
| Version model | Single version clock per VolumeSeries | Independent version clock per (series_key, layer) | Aligns with FR-055/055a — each layer is independently versioned |
| Granularity | `effectiveGranularity` per interval (mixed in single series) | One uniform granularity per series; always base for METERED_ACTUAL | Store-as-uploaded; no forced coarsening |
| Consumption contract | Internal methods (getContractualIntervals, etc.) | Unified `queryVolumeForTradeLeg(tradeLegId, purpose, intervalRange)` | One query path for all trades |

### 1.3 What Is Preserved from V2.1

- VolumeFormula and its sub-entities (ShapingEntry, SeasonalAdjustment) — unchanged
- TimeGranularity enum — unchanged
- VolumeUnit enum (MW_CAPACITY / MWH_PER_PERIOD) — unchanged
- ProfileType enum — unchanged
- DST handling rules (section 5 verbatim) — unchanged
- Energy calculation logic (Instant-based arithmetic, BigDecimal scale 6) — unchanged
- Rolling horizon materialization for long-tenor series — preserved
- Chunk processing pattern (Kafka message per month) — preserved
- Energy conservation invariant — preserved

---

## 2. Domain Context

### 2.1 EU Physical Power Market Characteristics

EU physical power markets operate on delivery intervals that vary by market and product type. The German bidding zone (DE-LU) uses 15-minute intervals for intraday continuous trading (EPEX SPOT / XBID), while day-ahead auctions clear on hourly or 15-minute products depending on the exchange. Bilateral OTC contracts and PPAs may use 30-minute, hourly, daily, or monthly granularity.

All delivery times are expressed in the delivery timezone (typically `Europe/Berlin` for CET/CEST), not UTC. This is critical because:

- CET/CEST observes two DST transitions per year
- The October fall-back creates a 25-hour day (02:00-03:00 occurs twice)
- The March spring-forward creates a 23-hour day (02:00-03:00 is skipped)
- Energy settlement is based on actual elapsed time, not nominal hours

### 2.2 Product Types

| Product | Typical Granularity | Typical Tenor | Materialization |
|---|---|---|---|
| DA Auction (EPEX SPOT) | 15 min | Single day | Full, immediate |
| Intraday Continuous | 15 min | Single interval to hours | Full, immediate |
| Short Block | 30 min / 1 hr | 1-6 hours | Full, immediate |
| Monthly Forward | Monthly | 1 month | Full, immediate |
| Annual Baseload | Monthly / Hourly | 1 year | Rolling horizon |
| PPA (Solar/Wind) | 15 min | 3-15 years | Rolling horizon |

### 2.3 Unified Volume Model

All trades — regardless of product type — follow ONE resolution pattern:

```
trade_volume = volume_series_interval.volume x reference.multiplier
```

Every trade has a `VolumeReference` that points to a `VolumeSeries`. The difference between trade types is merely the _characteristics_ of the VolumeSeries and the multiplier value:

| Trade Type | VolumeSeries.seriesType | Ownership | multiplier | meteredSeriesKey |
|---|---|---|---|---|
| PPA (generation-following) | FORECAST | Per asset, shared | 0.30 (trade's share) | Set (asset's meter) |
| DA fill | PROFILE | Per trade, dedicated | 1.0 | null (settlement uses same volume) |
| Bilateral flat block | PROFILE | Per trade, dedicated | 1.0 | null |
| Monthly forward | PROFILE | Per trade, dedicated | 1.0 | null |

```
UNIFIED MODEL — Every trade resolves volume the same way:
================================================================

Asset "WindPark-Nordsee" (100 MW rated)
|
+-- VolumeSeries (seriesType=FORECAST, assetId="WP-NORDSEE")
|     +-- VolumeInterval (0:N, full asset forecast output)
|
+-- MeteredActualVolumeSeries (per asset, unchanged)
|     +-- MeteredActualInterval (0:N, full asset metered output)
|
+-- VolumeReference (trade=T-7788, multiplier=0.30)
|     volumeSeriesKey -> FCST-WP-NORDSEE
|     meteredSeriesKey -> MTR-WP-NORDSEE
|
+-- VolumeReference (trade=T-8899, multiplier=0.30)
|     volumeSeriesKey -> FCST-WP-NORDSEE
|     meteredSeriesKey -> MTR-WP-NORDSEE
|
+-- VolumeReference (trade=T-9900, multiplier=0.40)
      volumeSeriesKey -> FCST-WP-NORDSEE
      meteredSeriesKey -> MTR-WP-NORDSEE


DA Fill "T-5500" (50 MW baseload, 24 Apr 2026)
|
+-- VolumeSeries (seriesType=PROFILE, tradeLegId="LEG-5500-1")
|     +-- VolumeInterval (96 intervals, each 50 MW)
|
+-- VolumeReference (trade=T-5500, multiplier=1.0)
      volumeSeriesKey -> VS-T5500-1
      meteredSeriesKey -> null (settlement uses same PROFILE volume)
```

### 2.4 Position in the Trade Capture Pipeline

```
Asset Onboarding --> VolumeSeries (seriesType=FORECAST, per asset)
                     MeteredActualVolumeSeries (per asset)

Trade Capture -> trade.captured -> Detail Generation Service
                                         |
                                         v
                              VolumeReference (links trade to volume)
                              + VolumeSeries (seriesType=PROFILE, if fixed-profile trade)
                                         |
                                         v
                              Position / Valuation Service
                              (reads VolumeSeries x multiplier — ONE path for all trades)
```

The two volume aggregates have different ownership:
- **VolumeSeries (FORECAST)**: per asset, populated from asset management / weather model feeds; shared across trades
- **VolumeSeries (PROFILE)**: per trade-leg, populated from trade capture (Detail Generation Service); dedicated to one trade
- **MeteredActualVolumeSeries**: per asset, populated from TSO metering data; shared across trades

---

## 3. Domain Model

### 3.1 Entity Relationships

The model uses a SINGLE resolution pattern for all trades. The distinction is not in the code path but in the _properties_ of the linked VolumeSeries:

```
UNIFIED RESOLUTION (all trades):
=================================

VolumeSeries (root aggregate)
|-- seriesType: FORECAST | PROFILE
|-- assetId: nullable (set for FORECAST)
|-- tradeLegId: nullable (set for PROFILE)
|-- Constraint: exactly one of assetId/tradeLegId must be set
|-- VolumeInterval (0:N, the volume data)
|
VolumeReference (links trade-leg to volume series)
|-- tradeLegId: always set
|-- assetId: nullable (set for asset-linked trades)
|-- multiplier: 0.30 for PPAs, 1.0 for fixed-profile
|-- volumeSeriesKey: points to the VolumeSeries (FORECAST or PROFILE)
|-- meteredSeriesKey: nullable (null for exchange trades)
|-- effectiveFrom/To: own date range
|-- VolumeFormula (1:1, contract-level parameters)
|
MeteredActualVolumeSeries (root aggregate, per asset only — unchanged)
|-- MeteredActualInterval (0:N, full asset metered output)
|
CompactionView (read model, optional)
+-- CompactedInterval (0:N, coarsened intervals for display)


Resolution:
  trade_volume = volume_series_interval.volume x reference.multiplier

For PPAs:
  multiplier < 1.0
  volumeSeriesKey -> shared asset FORECAST series
  meteredSeriesKey -> asset's MeteredActualVolumeSeries

For DA/bilateral:
  multiplier = 1.0
  volumeSeriesKey -> per-trade PROFILE series (few intervals)
  meteredSeriesKey = null (settlement uses same PROFILE volume)
```

**Ownership summary:**

| Aggregate | Keyed by | seriesType | Row cost |
|---|---|---|---|
| VolumeSeries (FORECAST) | assetId | FORECAST | 1 per asset (shared across N trades) |
| VolumeSeries (PROFILE) | tradeLegId | PROFILE | 1 per fixed-profile trade + N intervals |
| VolumeReference | (tradeLegId, volumeSeriesKey) | N/A | 1 per trade-leg (metadata only, no intervals) |
| MeteredActualVolumeSeries | assetId | N/A | 1 per asset (shared) |

**Why a fixed-profile trade is a degenerate case:**

For a DA fill at 50 MW baseload on 24 Apr 2026:
- A `VolumeSeries(seriesType=PROFILE)` is created with 96 intervals of 50 MW
- A `VolumeReference` points to it with `multiplier=1.0`
- Resolution: `50 MW x 1.0 = 50 MW` — identical to reading the interval directly

This is semantically equivalent to how a fixed price (e.g., EUR 45.00/MWh) is a degenerate `PriceExpression` with a constant function. The unified code path handles both cases without branching.

**Why NOT store separate contractual intervals for asset-linked trades:**

For a generation-following PPA, the "contractual volume" at any point in time is:
- Before delivery: `forecast x multiplier`
- After delivery: `meter x multiplier`

Storing a separate set of contractual intervals would mean:
1. **Redundancy**: The intervals would simply be `forecast x multiplier` — the same data, duplicated
2. **Staleness**: Every forecast update would require regenerating the contractual intervals
3. **Scale**: A 10-year 15-min PPA = ~3.5M contractual intervals that serve no purpose beyond what `multiplier x asset_series` already provides
4. **Semantic confusion**: The "contractual" obligation for a generation-following deal is NOT a fixed volume — it's "your share of whatever happens"

The VolumeFormula (on `VolumeReference`) records the recipe: `forecastSourceId` + `multiplier`. This IS the contractual definition. No materialization needed.

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
| `MW_CAPACITY` | Volume represents power capacity in MW | `energy = volume x elapsed hours` |
| `MWH_PER_PERIOD` | Volume represents energy delivered per period in MWh | `energy = volume` |

#### 3.2.3 ProfileType (preserved from V2.1)

| Value | Description |
|---|---|
| `BASELOAD` | Flat volume 24/7 |
| `PEAKLOAD` | Mon-Fri 08:00-20:00 (market-specific) |
| `OFFPEAK` | Inverse of peakload |
| `SHAPED` | Custom volume per time-of-use block |
| `BLOCK` | Named block product |
| `GENERATION_FOLLOWING` | Linked to renewable forecast |

#### 3.2.4 SeriesType (NEW)

Distinguishes the two kinds of VolumeSeries within the unified model.

| Value | Ownership | Characteristics | Example |
|---|---|---|---|
| `FORECAST` | Per asset (shared) | Weather-model-sourced, frequently updated, mutable (superseded on re-forecast) | Asset's expected generation output |
| `PROFILE` | Per trade-leg (dedicated) | Contractual, created once at trade capture, few intervals, immutable after capture | DA fill: 96 intervals of 50 MW |

#### 3.2.5 VolumeLayer (NEW)

Identifies which independent volume layer a data point belongs to. Maps to position-valuation section 7 FR-051 layers.

| Value | Source Aggregate | Meaning |
|---|---|---|
| `VOLUME` | `VolumeSeries` (either FORECAST or PROFILE) | The trade's expected or contractual volume, resolved via VolumeReference |
| `METERED_ACTUAL` | `MeteredActualVolumeSeries` | What was actually delivered — metered data from TSO |

**Layer resolution:**
- For FORWARD valuation: read `VolumeReference.volumeSeriesKey` (the VolumeSeries — either FORECAST or PROFILE) x multiplier
- For SETTLEMENT valuation: read `VolumeReference.meteredSeriesKey` x multiplier (if set); else fall back to `volumeSeriesKey` x multiplier

#### 3.2.6 QualityState (NEW)

Quality progression state per layer. Aligns with FR-054 `quality_state` field definition.

| Value | Applicable To | Meaning |
|---|---|---|
| `EFFECTIVE` | PROFILE series | Trade is booked; intervals represent active obligation |
| `AMENDED` | PROFILE series | Superseded by trade amendment (historical version) |
| `CURRENT` | FORECAST series | Latest forecast version for the interval range |
| `SUPERSEDED` | FORECAST series | Replaced by a newer forecast |
| `PROVISIONAL` | METERED_ACTUAL | Initial meter read (D+1 typically) |
| `VALIDATED` | METERED_ACTUAL | Confirmed by TSO/settlement body |
| `ESTIMATED` | METERED_ACTUAL | Gap-filled estimate pending actual read |

#### 3.2.7 MaterializationStatus (preserved from V2.1)

| Value | Meaning |
|---|---|
| `PENDING` | Not yet generated |
| `PARTIAL` | Rolling horizon — near-term materialized, far-dated pending |
| `FULL` | All intervals generated |
| `FAILED` | Generation failed, awaiting DLQ retry |

#### 3.2.8 IntervalStatus (preserved from V2.1)

| Value | Meaning |
|---|---|
| `CONFIRMED` | Exchange-confirmed or bilateral agreed |
| `ESTIMATED` | Derived from formula, not yet confirmed |
| `PROVISIONAL` | Subject to reconciliation |
| `CANCELLED` | Amendment cancelled this interval |

### 3.3 Root Aggregates

#### 3.3.1 VolumeSeries (UNIFIED)

The unified volume series aggregate representing either an asset's forecast output (seriesType=FORECAST) or a trade's contractual profile (seriesType=PROFILE). One entity, two modes — resolved identically by consumers via `VolumeReference`.

**Constraint:** Exactly one of `assetId` / `tradeLegId` must be set:
- `seriesType=FORECAST` => `assetId` is set, `tradeLegId` is null
- `seriesType=PROFILE` => `tradeLegId` is set, `assetId` is null

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key (see §3.3.8 Implementation Note: ID Strategy) |
| `seriesKey` | `String` | Stable external key (e.g., `FCST-WP-NORDSEE` or `VS-T5500-1`) — survives amendments |
| `seriesType` | `SeriesType` | FORECAST or PROFILE |
| `assetId` | `String` (nullable) | FK to asset — set for FORECAST, null for PROFILE |
| `tradeLegId` | `String` (nullable) | FK to trade leg — set for PROFILE, null for FORECAST |
| `tradeId` | `String` (nullable) | FK to parent trade (set for PROFILE, denormalized for queries) |
| `tradeVersion` | `int` (nullable) | Trade version (set for PROFILE, optimistic lock) |
| `versionId` | `long` | Monotonically increasing version per (seriesKey, VOLUME layer) |
| `volumeUnit` | `VolumeUnit` | MW_CAPACITY or MWH_PER_PERIOD |
| `granularity` | `TimeGranularity` | Uniform interval width for this series |
| `deliveryStart` | `ZonedDateTime` | Start of delivery/forecast window (inclusive) |
| `deliveryEnd` | `ZonedDateTime` | End of delivery/forecast window (exclusive) |
| `deliveryTimezone` | `ZoneId` | Delivery timezone (e.g., `Europe/Berlin`) |
| `profileType` | `ProfileType` | Delivery profile classification |
| `qualityState` | `QualityState` | EFFECTIVE/AMENDED (PROFILE) or CURRENT/SUPERSEDED (FORECAST) |
| `forecastSourceId` | `String` (nullable) | External forecast source reference — set for FORECAST, null for PROFILE |
| `ratedCapacityMw` | `BigDecimal` (nullable) | Asset's rated capacity in MW — set for FORECAST, null for PROFILE |
| `publishedAt` | `Instant` (nullable) | When forecast was published — set for FORECAST, null for PROFILE |
| `materializationStatus` | `MaterializationStatus` | Tracks rolling-horizon progress |
| `materializedThrough` | `YearMonth` | Last fully materialized month (null if FULL/PENDING) |
| `totalExpectedIntervals` | `int` | Pre-calculated for progress tracking |
| `materializedIntervalCount` | `int` | Current count of materialized intervals |
| `transactionTime` | `Instant` | Bi-temporal: when system recorded this version |
| `validTime` | `Instant` (nullable) | Bi-temporal: when economically effective (set for PROFILE) |
| `formula` | `VolumeFormula` (nullable) | Contract-level volume definition — set for PROFILE |
| `intervals` | `List<VolumeInterval>` | Ordered by `intervalStart` |

#### 3.3.2 VolumeInterval (UNIFIED)

Replaces both `ForecastInterval` and `ContractualInterval` from the previous model.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent VolumeSeries |
| `intervalStart` | `ZonedDateTime` | Start (inclusive), in delivery timezone |
| `intervalEnd` | `ZonedDateTime` | End (exclusive), in delivery timezone |
| `volume` | `BigDecimal` | Volume value; interpretation per parent's `volumeUnit` |
| `energy` | `BigDecimal` | Derived MWh (see energy calculation section 5.5) |
| `status` | `IntervalStatus` | Lifecycle status |
| `chunkMonth` | `YearMonth` (nullable) | Which materialization chunk produced this (set for PROFILE rolling-horizon) |
| `version` | `int` | Forward-link append-only version (starts at 1; incremented on supersession) |
| `supersedes_id` | `UUID` (nullable) | Points to the interval this version replaces (null for first version) |

#### 3.3.3 VolumeReference (UNIVERSAL — all trades)

Links a trade-leg to its volume source. This is the SINGLE entry point for volume resolution — every trade has one (or more, for stepped allocations). Replaces the old `AssetVolumeReference` (which was Category-A-only).

For PPA trades: points to a shared asset FORECAST series with multiplier < 1.0
For DA/bilateral trades: points to a dedicated per-trade PROFILE series with multiplier = 1.0

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `tradeLegId` | `String` | FK to trade leg — **always set** |
| `tradeId` | `String` | FK to parent trade (denormalized for queries) |
| `assetId` | `String` (nullable) | FK to asset — set for asset-linked trades, null for exchange/bilateral |
| `multiplier` | `BigDecimal` | Trade-leg's share (0.30 for PPA = 30%; 1.0 for fixed-profile) |
| `volumeSeriesKey` | `String` | FK to VolumeSeries.seriesKey (FORECAST or PROFILE) |
| `meteredSeriesKey` | `String` (nullable) | FK to MeteredActualVolumeSeries.seriesKey — null for exchange trades (settlement uses volumeSeriesKey) |
| `effectiveFrom` | `ZonedDateTime` | Allocation start — see section 3.3.3a for why this is on the reference |
| `effectiveTo` | `ZonedDateTime` | Allocation end — see section 3.3.3a |
| `formula` | `VolumeFormula` | Contract-level parameters (tolerance band, seasonal adjustments) |

##### 3.3.3a Why `effectiveFrom/To` Lives on VolumeReference (Not Derived from Trade)

The date range is stored directly on `VolumeReference` rather than derived from the parent trade's delivery window. This is a deliberate design choice:

**Reasons FOR own date range (decided):**

1. **Stepped allocations**: A single trade may have different multipliers over time. Example: year-1 ramp-up at 0.20, then 0.30 from year 2. This requires two `VolumeReference` rows for the same `(asset, tradeLeg)` with different effective periods and multipliers.

2. **Partial unwinds**: If a trader sells back 10% of their allocation for the last 6 months, the original reference's `effectiveTo` is shortened and a new reference (with lower multiplier) covers the remainder. The trade itself is not amended — only the allocation is.

3. **Sub-range coverage**: A trade's delivery window might be 2026-2036, but the asset only connects to the grid in 2027. The `effectiveFrom` on the reference is 2027-01-01, while the trade's delivery start is 2026-08-01 (the trade may have a different interim arrangement).

4. **Query efficiency**: Resolving "which trade-legs are active on this asset for August 2027?" is a single indexed query on `(asset_id, effectiveFrom, effectiveTo)` — no join to the trade table required. This is critical for event fan-out performance (section 8.2).

5. **Amendment independence**: Changing the trade's delivery end (extension, early termination) may or may not change the allocation period. They are separate business decisions. Coupling them forces unnecessary coordination.

**Why NOT derive from trade (rejected):**

- "Single source of truth" argument: While appealing, it conflates two different facts. The trade's delivery window is "when the commercial relationship exists." The allocation period is "when this asset backs this trade." These are correlated but not identical.
- "Duplication risk" argument: Mitigated by a validation rule: `effectiveFrom >= trade.deliveryStart AND effectiveTo <= trade.deliveryEnd` (allocation cannot exceed trade period). This is a soft check, not a hard FK constraint, because trade amendments may temporarily violate it during amendment processing.

**For fixed-profile trades (DA, bilateral):** The `effectiveFrom/To` on the `VolumeReference` typically equals the trade's delivery window exactly, since the PROFILE series is dedicated to that trade. But it is still stored on the reference for consistency and to avoid special-casing.

**Multiplier rules:**

- `multiplier` is a `BigDecimal` in the range `(0, 1]` for asset-linked trades.
- For fixed-profile trades, `multiplier` is always `1.0`.
- The sum of all multipliers across all active trade-legs for one asset **within any overlapping time window** should normally equal `1.0` (fully allocated), but this is a **business validation**, not a hard constraint. Over-allocation (sum > 1.0) is a risk condition to flag, not an error to prevent.
- Under-allocation (sum < 1.0) represents uncontracted capacity — normal for assets not yet fully sold.

**How the multiplier is applied (universal resolution):**

```
trade_leg_volume = volume_series_interval.volume x multiplier
trade_leg_energy = volume_series_interval.energy x multiplier
```

The multiplier is applied at **consumption time**, not at storage time. The stored intervals always represent the full volume (full asset output for FORECAST; full contractual MW for PROFILE where multiplier=1.0). This ensures:
1. A forecast update is stored once, not N times for N trades
2. A meter reading is stored once, not N times for N trades
3. Multiplier changes (trade amendments) are metadata-only — zero volume data rewritten
4. The asset's total output is always directly queryable without reverse-engineering from trade shares
5. One resolution code path handles all trade types

**Worked example (PPA):**

```
Asset: WindPark-Nordsee, rated 100 MW
VolumeSeries (FORECAST): interval 2026-08-15 18:45: volume = 72.5 MW, energy = 18.125 MWh

VolumeReference #1:
  trade=T-7788, multiplier=0.30, effectiveFrom=2026-08-01, effectiveTo=2027-08-01
  -> trade_volume = 72.5 x 0.30 = 21.75 MW, trade_energy = 5.4375 MWh

VolumeReference #2:
  trade=T-8899, multiplier=0.30, effectiveFrom=2026-08-01, effectiveTo=2028-08-01
  -> trade_volume = 72.5 x 0.30 = 21.75 MW, trade_energy = 5.4375 MWh

VolumeReference #3:
  trade=T-9900, multiplier=0.40, effectiveFrom=2026-08-01, effectiveTo=2027-08-01
  -> trade_volume = 72.5 x 0.40 = 29.00 MW, trade_energy = 7.25 MWh

Sum for Aug 2026: 0.30 + 0.30 + 0.40 = 1.00 (fully allocated)
Sum for Aug 2027: 0.00 + 0.30 + 0.00 = 0.30 (only T-8899 still active)
```

**Worked example (DA fill — degenerate case):**

```
Trade: T-5500, DA fill, 50 MW baseload, 24 Apr 2026
VolumeSeries (PROFILE, tradeLegId="LEG-5500-1"):
  96 intervals, each volume = 50 MW, energy = 12.5 MWh

VolumeReference:
  trade=T-5500, multiplier=1.0, volumeSeriesKey="VS-T5500-1", meteredSeriesKey=null
  -> trade_volume = 50 x 1.0 = 50 MW (trivial multiplication)

Settlement: meteredSeriesKey is null, so settlement uses volumeSeriesKey x multiplier = same 50 MW
(Exchange trades settle at their traded volume; no physical meter)
```

**Stepped multiplier example:**

```
Asset: SolarPark-Bayern, rated 50 MW
Trade T-1234:
  VolumeReference #A: multiplier=0.20, effectiveFrom=2026-01-01, effectiveTo=2027-01-01 (ramp-up)
  VolumeReference #B: multiplier=0.30, effectiveFrom=2027-01-01, effectiveTo=2031-01-01 (full allocation)

Two rows for the same (asset, tradeLeg) — different periods, different multipliers.
```

#### 3.3.4 MeteredActualVolumeSeries (UNCHANGED)

What was actually delivered — metered data from TSO. Always at market base granularity (e.g., MIN_15 for EPEX DE_LU). Progresses through provisional -> validated states.

**Ownership: per asset, not per trade.** The physical meter sits on the asset (wind farm, solar park). One metered series represents the **full asset output**. Multiple trade-legs reference this series via `VolumeReference` with a multiplier to derive their share.

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

#### 3.3.5 MeteredActualInterval (UNCHANGED)

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `seriesId` | `UUID` | FK to parent MeteredActualVolumeSeries |
| `intervalStart` | `ZonedDateTime` | Start (inclusive) |
| `intervalEnd` | `ZonedDateTime` | End (exclusive) |
| `volume` | `BigDecimal` | Metered volume (MW or MWh) |
| `energy` | `BigDecimal` | Derived MWh |
| `qualityState` | `QualityState` | PROVISIONAL, VALIDATED, or ESTIMATED |
| `version` | `int` | Forward-link append-only version (starts at 1) |
| `supersedes_id` | `UUID` (nullable) | Points to the interval this version replaces (null for first version) |

#### 3.3.6 CompactionView (Optional Read Model)

A precomputed coarsened view of any series, created on user request. Not a source of truth — always regenerable from the underlying series.

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `sourceSeriesId` | `UUID` | FK to source series (any layer) |
| `sourceLayer` | `VolumeLayer` | Which layer was compacted (VOLUME or METERED_ACTUAL) |
| `targetGranularity` | `TimeGranularity` | Coarsened granularity |
| `createdAt` | `Instant` | When compaction was performed |
| `intervals` | `List<CompactedInterval>` | Coarsened intervals |

#### 3.3.7 CompactedInterval

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `viewId` | `UUID` | FK to parent CompactionView |
| `intervalStart` | `ZonedDateTime` | Start (inclusive) |
| `intervalEnd` | `ZonedDateTime` | End (exclusive) |
| `volume` | `BigDecimal` | Aggregated volume (energy-weighted average for MW_CAPACITY; sum for MWH_PER_PERIOD) |
| `energy` | `BigDecimal` | Sum of source interval energies |
| `sourceIntervalCount` | `int` | How many source intervals were compacted |

### 3.4 Preserved Sub-Entities

#### 3.4.1 VolumeFormula (unchanged from V2.1 section 3.3.3)

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Primary key |
| `referenceId` | `UUID` | FK to parent VolumeReference |
| `baseVolume` | `BigDecimal` | Base volume in MW (for flat profiles) |
| `minVolume` | `BigDecimal` | Tolerance band floor (MW) |
| `maxVolume` | `BigDecimal` | Tolerance band cap (MW) |
| `shapingEntries` | `List<ShapingEntry>` | Time-of-use volume blocks (null if baseload) |
| `forecastSourceId` | `String` | External generation forecast reference |
| `forecastMultiplier` | `BigDecimal` | Fraction of forecast (e.g., 0.9 = 90%) |
| `seasonalAdjustments` | `List<SeasonalAdjustment>` | Year/season volume modifiers |
| `calendarId` | `String` | Reference to holiday/trading calendar |

#### 3.4.2 ShapingEntry (unchanged from V2.1 section 3.3.4)

| Field | Type | Description |
|---|---|---|
| `applicableDays` | `Set<DayOfWeek>` | Which days this block applies to |
| `blockStart` | `LocalTime` | Block start (inclusive) |
| `blockEnd` | `LocalTime` | Block end (exclusive) |
| `volume` | `BigDecimal` | Volume in MW for this block |
| `appliesToHolidays` | `boolean` | Whether block applies on public holidays |
| `validFromMonth` | `Month` | Seasonal start (null = all months) |
| `validToMonth` | `Month` | Seasonal end |

#### 3.4.3 SeasonalAdjustment (unchanged from V2.1 section 3.3.5)

| Field | Type | Description |
|---|---|---|
| `fromMonth` | `Month` | Adjustment period start |
| `toMonth` | `Month` | Adjustment period end |
| `fromYear` | `Integer` | Year start (null = all years) |
| `toYear` | `Integer` | Year end |
| `multiplier` | `BigDecimal` | Multiplicative factor (e.g., 1.02 = +2%) |
| `absoluteAdj` | `BigDecimal` | Additive MW adjustment |

Application order: `adjustedVolume = (baseVolume x multiplier) + absoluteAdj`.

### 3.5 Implementation Note: ID Strategy (Dual-Key Pattern)

This spec uses `UUID` for all entity IDs at the domain model level. The persistence layer (`VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md`) uses a **dual-key pattern**:

- **Internal PK**: `BIGINT` with sequence-based generation (`allocationSize=50`) — used for all foreign keys, joins, and indexes. Provides optimal B-tree performance and smaller index footprint.
- **External identifier**: Separate `_uuid UUID NOT NULL UNIQUE` column — used for API exposure, cross-service references, and idempotency checks. Never used in JOINs.

This is a persistence optimization transparent to the domain model. All domain-level references use the logical UUID; the persistence layer maps internally.

---

## 4. Materialization Strategy

### 4.1 Store-As-Uploaded Principle

Each series stores intervals at the granularity provided by its data source:

| SeriesType | Granularity Rule | Example |
|---|---|---|
| PROFILE | Trade's contractual granularity, uniform across all intervals | Hourly bilateral -> all intervals HOURLY; 15-min DA -> all intervals MIN_15 |
| FORECAST | Whatever the forecast provider uploads | Weather model outputs at HOURLY; some at MIN_15 |
| METERED_ACTUAL | Always market base granularity | EPEX DE_LU -> always MIN_15 |

**No forced cascade coarsening.** A 10-year PPA at 15-min granularity stores its FORECAST at 15-min. The rolling horizon (section 4.2) limits how many intervals exist at any point in time; compaction (section 4.4) is available as an optional optimization.

### 4.2 Rolling Horizon for Long-Tenor Series

Preserved from V2.1 section 4.2. For long-tenor PROFILE series (multi-year bilateral forwards) or FORECAST series with extended coverage:

- Materialize a rolling window: M+1 through M+3 (configurable)
- Set `materializationStatus = PARTIAL`, `materializedThrough = M+3`
- Emit `VolumePublished` with `scope = PARTIAL`
- Enqueue monthly chunk jobs for remaining months as separate Kafka messages
- A monthly cron job extends the materialization window as time progresses

Short-term PROFILE series (DA, intraday, short blocks) are fully materialized immediately.

FORECAST series are typically uploaded in bulk by the weather model provider and do not use rolling horizon — they arrive fully populated for their coverage window.

### 4.3 Chunk Processing (preserved from V2.1 section 4.3)

Each monthly chunk is a separate Kafka message (`trade.detail.chunk.requested`) containing `{tradeId, tradeLegId, monthStart, monthEnd}`. This provides:

- **Parallelism**: Multiple months can be generated concurrently
- **Partial failure isolation**: If month 37 fails, months 1-36 remain valid
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

The Position Service consumes volume at the stored granularity through the unified resolution path:

| Scenario | Source | Consumer Responsibility |
|---|---|---|
| PROFILE intervals exist (DA/bilateral) | Read VolumeSeries(PROFILE) x multiplier (=1.0) | Consumer expands to atomic grid if needed (section 7) |
| FORECAST for forward marks (PPA) | Read VolumeSeries(FORECAST) x multiplier | Consumer expands to atomic grid if needed |
| Unmaterialized far-dated window | Read VolumeFormula for aggregate exposure | Contract-level position (no interval detail) |
| METERED_ACTUAL for settlement (PPA) | Read MeteredActualVolumeSeries x multiplier | Already at market base granularity |
| Settlement for exchange trades | Read VolumeSeries(PROFILE) x multiplier (=1.0) | Same as forward (no separate meter) |

---

## 5. DST Handling

*Preserved verbatim from V2.1 section 5.*

### 5.1 Why DST Matters

EU power settlement is based on actual delivered energy (MWh), which depends on actual elapsed time. On DST transition days, the nominal 24-hour day becomes 23 or 25 hours. Failing to handle this correctly creates reconciliation breaks against exchange settlement data.

### 5.2 Fall-Back (October, Last Sunday)

Clocks go from 03:00 CEST back to 02:00 CET. The hour 02:00-03:00 occurs twice.

- A full day has **25 hours**
- At 15-min granularity: **100 intervals** (not 96)
- At hourly granularity: **25 intervals** (not 24)
- Baseload 15 MW for the full day: **375 MWh** (not 360)

`ZonedDateTime.plus(Duration.ofMinutes(15))` handles this correctly because it operates on the underlying `Instant` and resolves back to the delivery timezone.

### 5.3 Spring-Forward (March, Last Sunday)

Clocks go from 02:00 CET to 03:00 CEST. The hour 02:00-03:00 does not exist.

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
energy = (volume x hours).setScale(6, HALF_UP)
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
for-all series S with ordered intervals [i_0, i_1, ..., i_n]:
  for-all k: S.intervals[k].intervalEnd == S.intervals[k+1].intervalStart
  S.intervals[0].intervalStart == S.deliveryStart
  S.intervals[n].intervalEnd == S.deliveryEnd (or materializedEnd for PARTIAL)
```

Each series is a single contiguous block at its uniform granularity.

### 6.3 Layer Independence

The two volume aggregates (VolumeSeries and MeteredActualVolumeSeries) are fully independent:

```
for-all operations on VolumeSeries:
  MeteredActualVolumeSeries is unaffected

for-all operations on MeteredActualVolumeSeries:
  VolumeSeries is unaffected
```

No layer derives from another layer. No operation on one layer triggers writes to another. Each layer has its own version clock, lifecycle, and data source.

Within VolumeSeries, FORECAST and PROFILE series are also independent of each other — a FORECAST supersession does not modify any PROFILE series, and vice versa.

### 6.4 Version Monotonicity

Within a (seriesKey, layer) pair, `versionId` is strictly monotonically increasing:

```
for-all supersession events for (seriesKey, layer):
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

### 6.6 Multiplier Allocation Invariant

For a given asset and overlapping time window, the sum of all active trade-leg multipliers should not exceed the asset's total capacity:

```
for-all asset A, for-all time window W:
  sum(ref.multiplier for all VolumeReference ref
      WHERE ref.assetId == A
      AND ref.effectiveFrom < W.end
      AND ref.effectiveTo > W.start)
  should <= 1.0
```

This is a **business validation** (flagged as a warning), not a hard constraint. Over-allocation may be intentional (staggered delivery schedules, merchant risk). Under-allocation (sum < 1.0) represents uncontracted capacity and is normal.

**Multiplier energy conservation:** The sum of trade-leg energies derived via multiplier must equal the asset's total energy when the asset is fully allocated:

```
If sum(multipliers) == 1.0:
  sum(asset_interval.energy x ref.multiplier for each ref) == asset_interval.energy
```

**Note:** For PROFILE series (fixed-profile trades), multiplier is always 1.0, so this invariant is trivially satisfied.

### 6.7 Formula Regenerability (preserved)

Any materialized VolumeInterval set for a PROFILE series must be exactly reproducible from the `VolumeFormula` + `TradingCalendar` + `granularity` + `deliveryTimezone`. This enables safe retry of failed chunks, re-materialization after amendment, and audit verification.

### 6.8 Interval Count Determinism (preserved)

For a given `(deliveryStart, deliveryEnd, granularity, deliveryTimezone)`, the expected interval count is deterministic and reproducible. `calculateExpectedIntervals()` must return the same value every time.

### 6.9 Bi-Temporal Completeness (preserved)

Every PROFILE `VolumeSeries` must have both `transactionTime` and `validTime` set for REMIT regulatory reporting.

### 6.10 Unified Resolution Invariant (NEW)

For all trades, regardless of product type, the volume resolution follows the same formula:

```
for-all trade-legs T with VolumeReference R pointing to VolumeSeries VS:
  trade_volume(T, interval) = VS.interval.volume x R.multiplier
```

There is no branching on trade type in the resolution code path. The multiplier value (1.0 vs < 1.0) and the seriesType (FORECAST vs PROFILE) are data differences, not code path differences.

---

## 7. Consumption Contract

This section defines the interface contract between the Volume Series module and the Position/Valuation service. It aligns with position-valuation functional spec section 7 FR-050 through FR-057a.

### 7.1 Query Interface

**FR-054 alignment.** The VolumeSeries module exposes volume data through a **single unified query interface** that resolves volume for any trade-leg through the same code path.

**Primary query signature:**

```
queryVolumeForTradeLeg(tradeLegId, purpose, intervalRange) -> List<VolumeRecord>
```

Where `purpose` is:
- `FORWARD`: reads `VolumeReference.volumeSeriesKey` x `multiplier` — used for forward marks and position
- `SETTLEMENT`: reads `VolumeReference.meteredSeriesKey` x `multiplier` (if meteredSeriesKey is set); else falls back to `volumeSeriesKey` x `multiplier` — used for settlement cells

**Resolution algorithm (unified for all trades):**

```
1. Look up VolumeReference(s) for tradeLegId where effectiveFrom <= intervalRange.end
   AND effectiveTo >= intervalRange.start
2. For each matching VolumeReference:
   a. If purpose == FORWARD:
      seriesKey = reference.volumeSeriesKey
   b. If purpose == SETTLEMENT:
      seriesKey = reference.meteredSeriesKey ?? reference.volumeSeriesKey
   c. Read intervals from the resolved series for intervalRange
   d. Apply: record.volume = interval.volume x reference.multiplier
             record.energy = interval.energy x reference.multiplier
3. Return VolumeRecords
```

**For PPA (trade T-7788, asset WindPark-Nordsee):**
- FORWARD: reads VolumeSeries(FORECAST, assetId="WP-NORDSEE") x 0.30
- SETTLEMENT: reads MeteredActualVolumeSeries(assetId="WP-NORDSEE") x 0.30

**For DA fill (trade T-5500, no asset):**
- FORWARD: reads VolumeSeries(PROFILE, tradeLegId="LEG-5500-1") x 1.0
- SETTLEMENT: meteredSeriesKey is null, falls back to volumeSeriesKey x 1.0 (same result)

**Low-level query signatures (for direct access):**

```
-- Read volume series intervals directly (no multiplier application):
queryVolumeSeriesIntervals(seriesKey, intervalRange) -> List<VolumeInterval>

-- Read metered actual intervals directly (no multiplier application):
queryMeteredActualIntervals(seriesKey, intervalRange) -> List<MeteredActualInterval>
```

**VolumeRecord fields (per FR-054):**

| Field | Type | Description |
|---|---|---|
| `interval_start` | `ZonedDateTime` | Half-open interval start in market-local wall-clock |
| `interval_end` | `ZonedDateTime` | Half-open interval end |
| `volume_mw` | `BigDecimal` | Average MW for the interval (**after multiplier**) |
| `volume_mwh` | `BigDecimal` | Energy for the interval (**after multiplier**) |
| `version_id` | `long` | Series-version identifier for this data point |
| `quality_state` | `QualityState` | Per FR-054: EFFECTIVE/AMENDED for PROFILE; CURRENT/SUPERSEDED for FORECAST; PROVISIONAL/VALIDATED/ESTIMATED for METERED_ACTUAL |
| `series_type` | `SeriesType` (nullable) | FORECAST or PROFILE (null for METERED_ACTUAL records) |
| `asset_id` | `String` (nullable) | Asset reference (set for FORECAST/METERED_ACTUAL, null for PROFILE) |
| `multiplier_applied` | `BigDecimal` | The multiplier used (1.0 for PROFILE; for audit trail) |

### 7.2 Granularity Expansion Responsibility

**FR-054a alignment.** The stored granularity may differ from the consumer's atomic grid:

| Scenario | Stored Granularity | Consumer Grid | Responsibility |
|---|---|---|---|
| PROFILE hourly, position grid 15-min | HOURLY | MIN_15 | Consumer expands: 1 hourly -> 4 x 15-min (same MW, energy/4) |
| PROFILE monthly, position grid 15-min | MONTHLY | MIN_15 | Consumer expands using VolumeFormula profile |
| FORECAST 15-min, position grid 15-min | MIN_15 | MIN_15 | No expansion needed |
| METERED_ACTUAL 15-min, settlement cells | MIN_15 | MIN_15 | No expansion needed (always at base) |

**Expansion rules for MW_CAPACITY:**
- MW value replicates to each sub-interval (intensive property)
- Energy divides proportionally by duration

**Expansion rules for MWH_PER_PERIOD:**
- Energy divides uniformly across sub-intervals (each gets `source.energy / N`)
- MW derived from energy: `mw = (energy / N) / sub_interval_hours`

### 7.3 Layer Resolution (per FR-051a)

The valuation layer determines which volume to read based on valuation purpose:

| Context | purpose Argument | Resolution Path | Multiplier |
|---|---|---|---|
| Undelivered interval, forward mark (S5b) | FORWARD | VolumeReference.volumeSeriesKey -> VolumeSeries x multiplier | Trade-leg's multiplier |
| Delivered interval, settlement cell (S5a) | SETTLEMENT | VolumeReference.meteredSeriesKey -> MeteredActualVolumeSeries x multiplier (if set); else volumeSeriesKey x multiplier | Trade-leg's multiplier |
| Fan-out for slot cache / grid display | FORWARD | VolumeReference.volumeSeriesKey -> VolumeSeries x multiplier | Trade-leg's multiplier |

**For PPA trades:** FORWARD reads the FORECAST series; SETTLEMENT reads the MeteredActual series. Both apply the same multiplier (e.g., 0.30).

**For DA/bilateral trades:** Both FORWARD and SETTLEMENT read the same PROFILE series (meteredSeriesKey is null, so SETTLEMENT falls back). Multiplier is 1.0. Exchange trades settle at their traded volume; there is no physical meter to read.

**FR-051b: No automatic cross-layer fallback.** Missing data produces an explicit gap, never a substituted value from another layer.

### 7.4 Version Stamping (per FR-056)

Every valuation cell's input-version-set includes the volume `version_id` used in resolution, keyed by `(series_key, layer)`. This makes settlement cells reproducible.

---

## 8. Event Model

### 8.1 VolumePublished

Emitted when a new volume series version is created (first publication or after re-materialization).

| Field | Type | Description |
|---|---|---|
| `series_key` | `String` | e.g., `FCST-WP-NORDSEE` or `VS-T5500-1` |
| `layer` | `VolumeLayer` | VOLUME or METERED_ACTUAL |
| `series_type` | `SeriesType` | FORECAST or PROFILE (null for METERED_ACTUAL) |
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
| `series_key` | `String` | e.g., `FCST-WP-NORDSEE` or `VS-T5500-1` |
| `layer` | `VolumeLayer` | VOLUME or METERED_ACTUAL |
| `series_type` | `SeriesType` | FORECAST or PROFILE (null for METERED_ACTUAL) |
| `affected_range` | `[start, end)` | Half-open interval range whose volume changed |
| `old_version_id` | `long` (nullable) | Version being superseded (null for first publication) |
| `new_version_id` | `long` | New version identifier |
| `quality_state` | `QualityState` | New quality state (e.g., PROVISIONAL -> VALIDATED) |
| `event_time` | `Instant` | Processing timestamp (becomes `known_from` on resulting valuation-cell versions) |

**Consumer behavior (per FR-052b):**

1. On **METERED_ACTUAL** supersession -> look up all trade-legs referencing this asset (via `VolumeReference` where `meteredSeriesKey` matches); re-resolve settlement cells (S5a) for each trade-leg's affected intervals via dependency index, applying each trade-leg's multiplier
2. On **VOLUME (FORECAST)** supersession -> look up all trade-legs referencing this asset (via `VolumeReference` where `volumeSeriesKey` matches); overwrite forward marks (S5b) for each trade-leg's affected intervals (ephemeral, no bitemporality), applying each trade-leg's multiplier
3. On **VOLUME (PROFILE)** supersession -> informational to volume consumers; position/valuation cascade is driven by the trade-amendment event (per FR-052b point 4). A PROFILE supersession only occurs on trade amendment.

**Fan-out on asset events:** Because FORECAST and METERED_ACTUAL are per asset, a single supersession event for one asset may trigger revaluation for **multiple trade-legs**. The dependency index must carry edges from (asset series, interval) to all trade-legs that reference the asset. This is the primary reason for the `VolumeReference` lookup table.

**Fan-out unchanged for PROFILE:** A PROFILE supersession affects exactly one trade-leg (the one that owns the series). No fan-out needed.

### 8.3 VolumeChunkMaterialized

Emitted when a chunk of a PARTIAL series is materialized (rolling-horizon extension).

| Field | Type | Description |
|---|---|---|
| `series_key` | `String` | e.g., `VS-T5500-1` |
| `layer` | `VolumeLayer` | VOLUME |
| `series_type` | `SeriesType` | Typically PROFILE (only PROFILE uses rolling horizon for long-tenor bilateral) |
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

The unified interval table and metered actual interval table are partitioned by **delivery month** (the month of `interval_start`):

```sql
-- Unified volume intervals (replaces both forecast_interval and contractual_interval)
CREATE TABLE volume_interval (
    id UUID PRIMARY KEY,
    series_id UUID NOT NULL,
    interval_start TIMESTAMPTZ NOT NULL,
    interval_end TIMESTAMPTZ NOT NULL,
    volume NUMERIC NOT NULL,
    energy NUMERIC NOT NULL,
    status TEXT NOT NULL,
    chunk_month TEXT
) PARTITION BY RANGE (interval_start);

-- Metered actual intervals (unchanged)
CREATE TABLE metered_actual_interval (
    id UUID PRIMARY KEY,
    series_id UUID NOT NULL,
    interval_start TIMESTAMPTZ NOT NULL,
    interval_end TIMESTAMPTZ NOT NULL,
    volume NUMERIC NOT NULL,
    energy NUMERIC NOT NULL
) PARTITION BY RANGE (interval_start);
```

**pg_partman configuration:**
- Partition interval: 1 month
- Pre-create: 6 months ahead
- Retention: managed by pg_cron job (see section 9.3)

### 9.3 Retention Policy

| Regulation | Period | Scope |
|---|---|---|
| REMIT | 5 years from transaction | Transaction records |
| MiFID II | 5-7 years | Transaction records |
| EMIR | 5 years from termination | Derivative contracts |
| GDPR | Delete after retention | Personal data |

**Binding ceiling: 7 years post-settlement finalization.** Partitions are dropped when all intervals in the partition exceed `settlement_finalized_at + 7 years + 6 months`.

**pg_cron retention job** (runs monthly):
```sql
-- Pseudocode: drop partitions older than retention threshold
SELECT pg_partman.drop_partition_id(
    p_parent_table := 'public.volume_interval',
    p_retention := '90 months'  -- 7.5 years
);
SELECT pg_partman.drop_partition_id(
    p_parent_table := 'public.metered_actual_interval',
    p_retention := '90 months'
);
```

### 9.4 Row Count Estimates (V3.0 Unified Model)

Because (a) FORECAST series are per **asset** (not per trade), (b) PPA trades have no PROFILE series (they point to the asset's FORECAST series directly), and (c) fixed-profile trades use `volume_interval` instead of the old `contractual_interval`, the storage model is streamlined.

#### 9.4.1 Per-Entity Row Counts

| SeriesType / Layer | Rows per entity per month | Formula | Notes |
|---|---|---|---|
| PROFILE (per fixed-profile trade) | ~2,976 | `days_in_month x intervals_per_day` (31 x 96) | Only for DA fills, bilateral, monthly forwards |
| FORECAST (per asset) | ~2,976 or ~744 | `days x (96 or 24)` depending on provider granularity | Stored once per asset; shared by N trade-legs |
| METERED_ACTUAL (per asset) | ~2,976 | `days x 96` (always 15-min) | Stored once per asset; shared by N trade-legs |
| VolumeReference | 1-3 | Constant per (asset/trade, trade-leg, period) | Negligible metadata; no intervals |

#### 9.4.2 Platform Sizing Derivation

**Assumptions:**
```
T  = 200 tenants
A  = 8 distinct assets per tenant (wind/solar farms)
N  = 2.5 avg trade-legs per asset (e.g., one farm sold to 2-3 buyers)
P  = 20 PPAs per tenant total (= A x N = 8 x 2.5)
Bf = 5 fixed-profile trades per tenant (DA fills, bilateral)
M  = 12 hot months of delivery data
D  = 30.4 avg days per month
I  = 96 intervals per day (15-min granularity)
Ih = 24 intervals per day (hourly granularity)
```

**Formulas and results:**

| Table | Source | Formula | Calculation | Result |
|---|---|---|---|---|
| `volume_interval` (PROFILE) | DA/bilateral trades | `T x Bf x M x D x I` | 200 x 5 x 12 x 30.4 x 96 | **~35M rows** |
| `volume_interval` (FORECAST) | Asset forecasts (hourly) | `T x A x M x D x Ih` | 200 x 8 x 12 x 30.4 x 24 | **~14M rows** |
| `volume_interval` (FORECAST, 15-min) | Asset forecasts (15-min) | `T x A x M x D x I` | 200 x 8 x 12 x 30.4 x 96 | **~56M rows** |
| `metered_actual_interval` | Asset meters | `T x A x M_delivered x D x I` | 200 x 8 x 6 x 30.4 x 96 | **~28M rows** |
| `volume_reference` | All trade-legs | `T x (P + Bf)` | 200 x 25 | **5,000 rows** |
| **Total hot (hourly forecast)** | | | | **~77M rows** |
| **Total hot (15-min forecast)** | | | | **~119M rows** |

**Comparison to V2.1 model (where all data was per trade-leg):**

| Scenario | V2.1 rows | V3.0 rows | Savings |
|---|---|---|---|
| Contractual intervals for PPAs | T x P x M x D x I = 200 x 20 x 12 x 30.4 x 96 = **~140M** | **0** (PPAs point to FORECAST, no PROFILE) | 100% eliminated |
| Forecast intervals | T x P x M x D x Ih = 200 x 20 x 12 x 30.4 x 24 = **~35M** | T x A x M x D x Ih = **~14M** | 60% reduction |
| Metered intervals | T x P x M x D x I/2 = **~70M** | T x A x M_del x D x I = **~28M** | 60% reduction |
| Fixed-profile (DA/bilateral) | In contractual_interval: ~35M | In volume_interval: **~35M** | Same (table renamed) |
| **Total** | **~280M** | **~77M-119M** | **57-72% reduction** |

The savings come from two sources:
1. **No PROFILE series for PPAs** (~140M rows eliminated — PPAs use shared FORECAST series)
2. **Per-asset storage** for forecast/meter (2.5x fewer rows than per-trade storage)

Aurora PG 16 handles this volume comfortably with proper partitioning, indexing on `(series_id, interval_start)`, and connection pooling.

### 9.5 Index Strategy

```sql
-- Primary access pattern: query by series + time range (unified table)
CREATE INDEX idx_volume_interval_series_time
    ON volume_interval (series_id, interval_start);

-- Metered actual: same pattern
CREATE INDEX idx_metered_series_time
    ON metered_actual_interval (series_id, interval_start);

-- VolumeSeries: lookup by series_key + version
CREATE INDEX idx_volume_series_key_version
    ON volume_series (series_key, version_id);

-- VolumeSeries: lookup by asset (for fan-out)
CREATE INDEX idx_volume_series_asset
    ON volume_series (asset_id) WHERE asset_id IS NOT NULL;

-- VolumeSeries: lookup by trade-leg
CREATE INDEX idx_volume_series_trade_leg
    ON volume_series (trade_leg_id) WHERE trade_leg_id IS NOT NULL;

-- VolumeReference: "which trade-legs reference this asset?"
-- Critical for fan-out on forecast/meter supersession events
CREATE INDEX idx_vol_ref_asset
    ON volume_reference (asset_id) WHERE asset_id IS NOT NULL;

-- VolumeReference: "what volume does this trade-leg use?"
-- Used by queryVolumeForTradeLeg
CREATE INDEX idx_vol_ref_trade_leg
    ON volume_reference (trade_leg_id);

-- VolumeReference: "which references point to this series?"
-- Used for supersession fan-out
CREATE INDEX idx_vol_ref_volume_series_key
    ON volume_reference (volume_series_key);

CREATE INDEX idx_vol_ref_metered_series_key
    ON volume_reference (metered_series_key) WHERE metered_series_key IS NOT NULL;
```

---

## 10. Test Specification

### 10.1 Test Structure

```
VolumeSeriesV3Test
|-- UnifiedResolutionTests (NEW - validates one code path for all trades)
|   |-- PPAResolution (FORECAST series x multiplier=0.30)
|   |-- DAFillResolution (PROFILE series x multiplier=1.0)
|   |-- BilateralResolution (PROFILE series x multiplier=1.0)
|   |-- MonthlyForwardResolution (PROFILE series x multiplier=1.0)
|   |-- SameCodePathForBothTypes (no branching on trade type)
|   |-- DegenerateCaseEquivalence (PROFILE x 1.0 == direct interval read)
|   |-- SettlementWithMeter (PPA: reads MeteredActual x multiplier)
|   |-- SettlementWithoutMeter (DA: falls back to PROFILE x 1.0)
|   +-- SteppedMultiplierResolution (two references, different periods)
|
|-- VolumeSeriesTests (unified aggregate)
|   |-- ForecastUploadAtHourlyGranularity (stored as HOURLY, not coarsened)
|   |-- ForecastUploadAt15MinGranularity (stored as MIN_15)
|   |-- ForecastSupersession (new version supersedes old, versionId increases)
|   |-- ForecastQualityStateTransition (CURRENT -> SUPERSEDED on new upload)
|   |-- ForecastPartialRangeSupersession (only affected intervals change)
|   |-- ProfileSingleInterval (15-min block, both VolumeUnit modes)
|   |-- ProfileMultiInterval (1-hour at 15-min, contiguity, energy)
|   |-- ProfileFullDay (full materialization, interval count, energy totals)
|   |-- ProfilePartialMaterialization (M+3 rolling horizon)
|   |-- ProfileChunkMaterialization (extends window, promotes to FULL)
|   |-- ProfileFormulaRegeneration (intervals reproducible from formula)
|   |-- ProfileImmutability (intervals unchanged after creation)
|   |-- VersionMonotonicity (versionId increases on supersession)
|   |-- UniformGranularity (all intervals have series granularity)
|   |-- SeriesTypeConstraint (FORECAST requires assetId; PROFILE requires tradeLegId)
|   +-- IndependenceForecastFromProfile (forecast changes don't affect profile series)
|
|-- MeteredActualVolumeSeriesTests (unchanged)
|   |-- AlwaysAtBaseGranularity (rejects non-base uploads)
|   |-- ProvisionalToValidated (quality_state progression)
|   |-- SupersessionOnValidation (new version for validated data)
|   |-- IndependenceFromVolumeSeries (forecast changes don't affect meter)
|   +-- AppendOnlySemantics (corrections create new versions, never update)
|
|-- CompactionTests
|   |-- 15MinToHourly (energy sum preserved, MW averaged)
|   |-- HourlyToDaily (DST day produces correct compacted energy)
|   |-- DailyToMonthly (variable days handled correctly)
|   |-- CompactionEnergyInvariant (source energy == compacted energy)
|   |-- CompactionDoesNotModifySource (source intervals unchanged)
|   +-- CompactionRegenerable (delete + recreate produces same result)
|
|-- AssetMultiplierTests (expanded for unified model)
|   |-- SingleTradeFullCapacity (multiplier=1.0 on PPA, trade volume == asset volume)
|   |-- ThreeTradesSplitCapacity (0.3+0.3+0.4=1.0, sum equals asset)
|   |-- MultiplierAppliedToForecast (trade_mw = asset_mw x multiplier)
|   |-- MultiplierAppliedToMeter (trade_energy = asset_energy x multiplier)
|   |-- FixedProfileMultiplierIsOne (DA fill: multiplier=1.0, trivial)
|   |-- ForecastSupersessionFansOutToAllTrades (3 PPA trades all revalued)
|   |-- MeterSupersessionFansOutToAllTrades (3 PPA trades all resettled)
|   |-- ProfileSupersessionAffectsOneTrade (DA fill: no fan-out)
|   |-- UnderAllocation (sum < 1.0, no error, uncontracted capacity)
|   |-- OverAllocationFlagged (sum > 1.0, warning not error)
|   |-- MultiplierChangeDoesNotRewriteVolumeData (intervals unchanged)
|   +-- QueryVolumeForTradeLegResolvesCorrectSeries
|
|-- LayerIndependenceTests
|   |-- ForecastWriteDoesNotAffectProfile
|   |-- ProfileWriteDoesNotAffectForecast
|   |-- MeterWriteDoesNotAffectVolumeSeriesAny
|   |-- IndependentVersionClocks (each series increments independently)
|   +-- IndependentQualityStates (each series tracks own state)
|
|-- ConsumptionContractTests
|   |-- UnifiedQueryPathForPPA (queryVolumeForTradeLeg with FORWARD)
|   |-- UnifiedQueryPathForDA (queryVolumeForTradeLeg with FORWARD)
|   |-- SettlementQueryForPPA (queryVolumeForTradeLeg with SETTLEMENT -> meter)
|   |-- SettlementQueryForDA (queryVolumeForTradeLeg with SETTLEMENT -> profile fallback)
|   |-- QueryReturnsStoredGranularity (no implicit expansion)
|   |-- VolumeRecordFieldCompleteness (all FR-054 fields present)
|   |-- VersionIdInResponse (usable for input-version-set)
|   |-- QualityStateInResponse (correct per series type)
|   +-- RangeQueryFiltering (only intervals in requested range returned)
|
+-- DSTTests (cross-cutting, all layers)
    |-- FallBackDayIntervalCount (100 at 15-min, 25 at hourly)
    |-- SpringForwardDayIntervalCount (92 at 15-min, 23 at hourly)
    |-- EnergyUsesInstantArithmetic (not wall-clock)
    |-- AnnualNetEffect (full-year energy equals 8760h x MW)
    +-- ContiguityAcrossDSTBoundary (no gaps or overlaps)
```

### 10.2 Key Test Scenarios (preserved from V2.1, adapted to unified model)

#### Single 15-min Interval (PROFILE series)

| Parameter | Value |
|---|---|
| Delivery window | 24 Apr 2026, 17:45 - 18:00 CET |
| Granularity | 15 min |
| Volume | 15 |
| Profile | BLOCK |

**Expected:**
- VolumeSeries(seriesType=PROFILE) with exactly 1 VolumeInterval produced
- VolumeReference with multiplier=1.0
- **MW_CAPACITY:** Energy = 15 x 0.25 = **3.75 MWh**; resolved = 3.75 x 1.0 = **3.75 MWh**
- **MWH_PER_PERIOD:** Energy = **15 MWh**; resolved = 15 x 1.0 = **15 MWh**

#### One-Year PPA (FORECAST series via reference)

| Parameter | Value |
|---|---|
| Asset | WindPark-Nordsee, 100 MW rated |
| Delivery window | 24 Apr 2026, 17:00 - 24 Apr 2027, 17:00 CET |
| Forecast Granularity | MIN_15 |
| Trade multiplier | 0.30 |
| Forecast volume | 72.5 MW average |

**Expected:**
- VolumeSeries(seriesType=FORECAST, assetId="WP-NORDSEE") with ~35,040 VolumeIntervals (365 x 96, +/- 4 for DST)
- VolumeReference with multiplier=0.30
- Resolved trade volume per interval: 72.5 x 0.30 = **21.75 MW**
- Total asset energy: ~131,400 MWh (if flat 15 MW equivalent avg)
- Total trade energy: ~131,400 x 0.30 = **~39,420 MWh**
- DST fall-back (25 Oct 2026): 100 intervals on asset
- DST spring-forward (28 Mar 2027): 92 intervals on asset

#### Degenerate Case Equivalence (DA fill)

| Parameter | Value |
|---|---|
| Trade | DA fill T-5500, 50 MW baseload |
| Delivery window | 24 Apr 2026, 00:00 - 25 Apr 2026, 00:00 CET |
| Granularity | MIN_15 |

**Expected:**
- VolumeSeries(seriesType=PROFILE, tradeLegId="LEG-5500-1") with 96 VolumeIntervals, each 50 MW
- VolumeReference with multiplier=1.0, meteredSeriesKey=null
- Resolved trade volume: 50 x 1.0 = 50 MW (trivial)
- FORWARD query: reads PROFILE x 1.0
- SETTLEMENT query: meteredSeriesKey is null, falls back to PROFILE x 1.0
- **Identical result to reading intervals directly** — the unified model is transparent for fixed-profile trades

#### Cross-Granularity Energy Invariant

| Test | VolumeUnit | Assertion |
|---|---|---|
| Energy conservation | MW_CAPACITY | 5-min, 15-min, 30-min, hourly all produce 15 MWh for 17:00-18:00 at volume=15 |
| MWH_PER_PERIOD semantics | MWH_PER_PERIOD | Total energy scales with interval count (4 x 15 = 60 at 15-min vs 2 x 15 = 30 at 30-min) |

---

## 11. Design Decisions & Rationale

### 11.1 Why Two Independent Aggregates (VolumeSeries + MeteredActual)

**V2.1 problem:** A single `VolumeSeries` with `BucketType` enum created coupling between layers that have fundamentally different lifecycles, sources, and mutability semantics. FORECAST intervals are mutable and weather-model-sourced; METERED_ACTUAL intervals are append-only and TSO-sourced. Forcing them into one aggregate violated the "independent lifecycle = independent aggregate" DDD principle.

**V3.0 resolution:** Two root aggregates:
1. `VolumeSeries` (unified, with `seriesType` distinguishing FORECAST from PROFILE)
2. `MeteredActualVolumeSeries` (separate, per-asset, append-only)

Why VolumeSeries unifies FORECAST and PROFILE but MeteredActual stays separate:
- FORECAST and PROFILE share the same resolution pattern (`volume x multiplier`) and serve the same consumer (forward valuation)
- MeteredActual has a fundamentally different lifecycle (append-only, TSO-sourced, settlement-only) and different quality state progression
- MeteredActual is always per-asset; VolumeSeries can be per-asset OR per-trade
- This aligns with FR-051: the VOLUME layer (from VolumeSeries) and METERED_ACTUAL layer are the two independent layers consumed by position/valuation

### 11.2 Why Unified VolumeSeries (Not Separate Contractual + Forecast)

**Previous design:** Separate `ContractualVolumeSeries` and `ForecastVolumeSeries` aggregates required two code paths for volume resolution — one for "Category A" (asset-linked) and one for "Category B" (fixed-profile).

**V3.0 resolution:** A single `VolumeSeries` with `seriesType` enum (FORECAST | PROFILE). The key insight:

**A fixed-profile trade is a DEGENERATE CASE of the unified model.** Its `VolumeSeries(seriesType=PROFILE)` is created per-trade with the trade's MW profile, and the `VolumeReference` points to it with `multiplier=1.0`. This mirrors how a fixed price is a degenerate `PriceExpression` (D-2) — a constant is a valid function. One code path resolves all trades:

```
trade_volume = volume_series_interval.volume x reference.multiplier
```

For PPAs: multiplier=0.30, series is shared FORECAST (weather model, frequently updated)
For DA: multiplier=1.0, series is dedicated PROFILE (created once at trade capture)

**Benefits of unification:**
1. **One resolution code path** — no branching on trade type
2. **One query interface** — `queryVolumeForTradeLeg(tradeLegId, purpose, range)`
3. **One interval table** — `volume_interval` replaces both `forecast_interval` and `contractual_interval`
4. **Conceptual clarity** — the model makes explicit that ALL trades consume volume the same way; the only differences are data properties (who owns the series, how often it updates, what multiplier applies)
5. **Extensibility** — a new trade type (e.g., battery storage with variable dispatch) just needs a new SeriesType value, not a new aggregate and code path

### 11.3 Why No PLAN Layer

**V2.1 design:** PLAN intervals were derived from CONTRACTUAL via cascade disaggregation (monthly -> daily -> 15-min). They existed to provide base-granularity volume for near-term operations.

**V3.0 removal:** With cascade removed (store-as-uploaded), there is no CONTRACTUAL source at coarser granularity to disaggregate from. The PLAN layer's purpose is now served by:
- PROFILE series already at base granularity (if trade is 15-min)
- FORECAST for forward marks on undelivered intervals (per FR-051a)
- Expansion at consumption time for coarser series (per section 7.2)

The position-valuation spec section 7 explicitly does NOT consume a PLAN layer — it consumes VOLUME and METERED_ACTUAL only. PLAN was an internal volume-module optimization, not a consumer-facing layer.

### 11.4 Why Store-As-Uploaded (Not Cascade)

**V2.1 design:** Cascade coarsening (NEAR=base, MED=daily, LONG=monthly) reduced storage for far-dated intervals, with a disaggregation cron to derive finer intervals as delivery approached.

**V3.0 removal reasons:**
1. **User requirement:** "Store volumes at upload granularity — no forced cascade coarsening"
2. **Complexity cost:** Cascade introduced CascadeTier, ScalarClassification (RATE/ABSOLUTE), disaggregation cron, cross-tier boundary alignment, mixed-granularity contiguity rules — all removed in V3.0
3. **Rolling horizon suffices:** A 10-year PPA doesn't need all 3.5M intervals materialized upfront. Rolling horizon (section 4.2) materializes M+3, extending monthly. Far-dated intervals are created only when needed.
4. **Optional compaction:** Users who want coarser views can request a CompactionView — but this is a read model, not the source of truth

### 11.5 Why Aurora PG 16 (Not TimescaleDB)

**Platform constraint:** The target infrastructure is Amazon Aurora PostgreSQL 16. TimescaleDB is not available on Aurora.

**Mitigation for lost TimescaleDB features:**
- **Compression** -> Aurora's built-in TOAST + pg_partman partition dropping replaces chunk compression
- **Continuous aggregates** -> CompactionView (section 3.3.6) serves the same purpose for volume roll-ups
- **Retention policies** -> pg_cron + pg_partman `drop_partition_id` (section 9.3)
- **Hypertable auto-partitioning** -> pg_partman range partitioning by delivery month

### 11.6 Why FORECAST and METERED_ACTUAL Are Per Asset (Not Per Trade)

**Domain truth:** A wind farm has one physical output. The weather doesn't know about trade contracts. The TSO meter reads the farm's total production, not each buyer's share. Storing forecast/meter data per trade would mean:
- N copies of the same data for N trades referencing the same asset
- A forecast update requires N writes instead of 1
- A meter correction requires N writes instead of 1
- No single-source view of the asset's actual performance

**V3.0 resolution:** FORECAST VolumeSeries and MeteredActualVolumeSeries are keyed by `assetId`. Trade-legs reference via `VolumeReference` with a `multiplier`. The multiplier is applied at consumption time (read path), not storage time (write path). This means:
1. One forecast update -> one write -> N revaluations (via fan-out)
2. One meter read -> one write -> N resettlements (via fan-out)
3. Multiplier changes (trade amendment, capacity reallocation) are metadata-only — no volume data rewrite
4. Asset-level analytics (total production, capacity factor) are direct queries with no reverse-engineering

### 11.7 ZonedDateTime Over UTC (preserved from V2.1)

Delivery times stored in delivery timezone (`Europe/Berlin`), not UTC. Preserves trader's mental model and contractual delivery semantics. UTC conversion loses DST fall-back disambiguation.

### 11.8 BigDecimal Over double (preserved from V2.1)

All volume and energy values use `BigDecimal` with scale 6. Avoids floating-point drift across 35,000+ interval summations.

### 11.9 Price Excluded from Interval Entities (preserved from V2.1)

Price is owned by the Pricing Service. Keeping it separate avoids write amplification when price curves update.

---

## Appendix A: FR Cross-Reference

| Position-Valuation FR | V3.0 Coverage |
|---|---|
| FR-050 (boundary: positions vs volumes) | section 2.3 pipeline diagram; two volume aggregates are operational, not position rows |
| FR-051 (volume layers consumed) | section 3.3 — VolumeSeries (VOLUME layer) + MeteredActualVolumeSeries (METERED_ACTUAL layer) |
| FR-051a (layer resolution rules) | section 7.3 — purpose-based resolution (FORWARD / SETTLEMENT) |
| FR-051b (no cross-layer fallback) | section 6.3 layer independence invariant; section 7.3 explicit gap behavior |
| FR-052 (react to supersession events) | section 8.2 VolumeSuperseded event; consumer behavior defined |
| FR-052a (VolumeSuperseded event payload) | section 8.2 — field-by-field match to FR-052a table |
| FR-053 (dual projection: risk + ops) | section 6.3 layer independence — layers serve different consumers independently |
| FR-054 (data shape and grain) | section 7.1 VolumeRecord fields; section 7.2 granularity expansion |
| FR-054a (grain conversion is consumer responsibility) | section 7.2 — explicit consumer responsibility table |
| FR-055 (independent versioning, supersession) | section 3.3.1/3.3.4 `versionId` field; section 6.4 version monotonicity |
| FR-055a (version identity, opaque to consumers) | section 7.4 version stamping; section 6.4 monotonicity rule |
| FR-055b (supersession triggers by layer) | section 8.2 consumer behavior section |
| FR-056 (version in input-version-set) | section 7.4 version stamping |
| FR-056a (fixed-profile trades, volume version) | section 7.3 — PROFILE series resolved same as FORECAST; version always tracked |
| FR-057 (dependency index edges) | section 8.2 consumer behavior — settlement cell and forward mark edges |
| FR-057a (active_leaves membership) | section 8.2 — dependency index lookup for supersession handling |

---

## Appendix B: Migration Notes (V2.1 -> V3.0)

### Removed Concepts (no V3.0 equivalent)

| V2.1 Concept | Disposition |
|---|---|
| `BucketType` enum | Replaced by `SeriesType` enum (FORECAST / PROFILE) + `VolumeLayer` enum (VOLUME / METERED_ACTUAL) |
| `ContractualVolumeSeries` aggregate | Merged into unified `VolumeSeries` with `seriesType=PROFILE` |
| `ForecastVolumeSeries` aggregate | Merged into unified `VolumeSeries` with `seriesType=FORECAST` |
| `ContractualInterval` entity | Replaced by unified `VolumeInterval` |
| `ForecastInterval` entity | Replaced by unified `VolumeInterval` |
| `AssetVolumeReference` (Cat A only) | Replaced by universal `VolumeReference` (all trades) |
| `contractual_interval` table | **Eliminated** — PPAs have no intervals; DA/bilateral use `volume_interval` |
| `forecast_interval` table | Merged into `volume_interval` table |
| Category A / Category B distinction | **Eliminated** — all trades follow unified resolution pattern |
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
| `VolumeSeries` (unified) | Single aggregate for both forecast and contractual profile data |
| `SeriesType` enum | Distinguishes FORECAST (asset-owned, mutable) from PROFILE (trade-owned, immutable) |
| `VolumeLayer` enum | VOLUME (from VolumeSeries) and METERED_ACTUAL (from MeteredActualVolumeSeries) |
| `VolumeReference` (universal) | Links ALL trade-legs to volume source with multiplier — replaces both AssetVolumeReference and direct ContractualVolumeSeries ownership |
| `VolumeInterval` (unified) | Single interval entity replacing ForecastInterval + ContractualInterval |
| `queryVolumeForTradeLeg(tradeLegId, purpose, range)` | Unified consumption interface — one query path for all trades |
| `purpose` enum (FORWARD / SETTLEMENT) | Determines which series key to resolve on VolumeReference |
| Degenerate case pattern | Fixed-profile trades are a special case of the general model (multiplier=1.0, per-trade PROFILE series) |

### Mapping for Existing Data

| V2.1 Row | V3.0 Destination |
|---|---|
| `ContractualVolumeSeries` (fixed-profile trades) | `VolumeSeries` with `seriesType=PROFILE`, `tradeLegId` set |
| `ContractualInterval` (fixed-profile trades) | `VolumeInterval` (same data, unified table) |
| `ContractualVolumeSeries` (PPA trades) | **Discarded** — PPAs now reference asset's FORECAST series via VolumeReference |
| `ContractualInterval` (PPA trades) | **Discarded** — no longer needed; volume derived from FORECAST x multiplier |
| `ForecastVolumeSeries` | `VolumeSeries` with `seriesType=FORECAST`, `assetId` set |
| `ForecastInterval` | `VolumeInterval` (same data, unified table) |
| `MeteredActualVolumeSeries` | Unchanged (stays as-is) |
| `MeteredActualInterval` | Unchanged (stays as-is) |
| `AssetVolumeReference` | `VolumeReference` with `assetId` set, `multiplier` preserved |
| Trade-to-ContractualVolumeSeries ownership (Cat B) | `VolumeReference` with `assetId=null`, `multiplier=1.0`, `volumeSeriesKey` pointing to the PROFILE series |
| VolumeInterval with `bucketType=PLAN` | **Discarded** (regenerable; no longer needed) |
| VolumeInterval with `bucketType=ACTUAL` | `metered_actual_interval` table (re-keyed to asset, volumes represent full asset output) |

### Migration Steps

1. **Create unified `volume_series` table** with `series_type` column
2. **Migrate `forecast_volume_series` rows** -> `volume_series` with `series_type='FORECAST'`
3. **Migrate `contractual_volume_series` rows** (fixed-profile only) -> `volume_series` with `series_type='PROFILE'`
4. **Discard `contractual_volume_series` rows** for PPA trades (replaced by VolumeReference -> FORECAST)
5. **Create unified `volume_interval` table**
6. **Migrate `forecast_interval` rows** -> `volume_interval`
7. **Migrate `contractual_interval` rows** (fixed-profile only) -> `volume_interval`
8. **Discard `contractual_interval` rows** for PPA trades
9. **Create `volume_reference` table**
10. **Migrate `asset_volume_reference` rows** -> `volume_reference` (preserve multiplier, effective dates)
11. **Create VolumeReference for each fixed-profile trade** with `multiplier=1.0`, `volumeSeriesKey` = migrated PROFILE series key, `meteredSeriesKey=null`
12. **Drop old tables**: `contractual_volume_series`, `forecast_volume_series`, `contractual_interval`, `forecast_interval`, `asset_volume_reference`
13. **Rebuild indexes** per section 9.5
14. **Verify**: run UnifiedResolutionTests against migrated data
