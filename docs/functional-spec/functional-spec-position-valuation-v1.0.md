# Functional Specification — Position & Valuation Model

**Document:** Position Generation, Price Expression & Valuation — Functional Specification
**Version:** 1.0 (Draft for review)
**Status:** DRAFT — pending review before technical specification phase
**Scope basis:** Design sessions on position generation for EU power CTRM, multi-granularity (15-min floor, 1-min extensible), multitenant SaaS
**Successor document:** Technical Specification (NOT yet authored; to be derived from this document after review)

---

## 1. Purpose & Scope

### 1.1 Purpose

This document specifies the functional model for **position generation, price expression, and position valuation** in the multitenant EU power CTRM platform. It defines the structures, their grains, lifecycles, population rules, derivation logic, and the read/write behaviors the system must exhibit — in sufficient depth that a technical specification (schemas, DDL, indexes, event contracts, APIs) can be derived from it without revisiting functional decisions.

### 1.2 In scope

- The **Position Ledger** (bitemporal, trade-leg-grained source of truth)
- The **PriceExpression** model (formula pricing for PPAs and indexed deals; fixed price as degenerate case)
- The **Market Data Store** interface (as consumed by valuation; series semantics, versioning)
- The **PositionValuation** layer (settlement cells, forward marks, EOD struck marks) and its persistence discipline
- The **Slot Cache** (netted atomic-interval materialization for grid display)
- **Rollup aggregates** for reporting and far-dated views
- Processing model: event-driven incremental maintenance and batch authoritative processing, dependency indexing, idempotency, reconciliation
- Read paths: position grid, drill-down, bitemporal as-of/audit queries
- Multitenancy, partitioning and retention at the functional level (what must be isolatable, prunable, retainable — not how)
- DST and market-calendar correctness rules
- Sizing model with derivations

### 1.3 Out of scope (owned elsewhere or deferred)

- The Volume Series module internals (all layers including PLAN, STRESS, scenario overlays, and BAV computation — specified in `VOLUME_SERIES_SPEC-V3_0.md`). This document specifies only the **interface contract** for the volume data consumed by position/valuation (§7): unified `VolumeSeries` (seriesType = FORECAST | PROFILE) consumed via `VolumeReference × multiplier`, plus `MeteredActualVolumeSeries` for delivered metering.
- Trade capture, confirmations, nominations, settlement/cashflow generation, regulatory report generation — this document specifies only the **data they must be able to obtain** from the structures herein.
- Non-power commodities. The core is designed to be commodity-neutral (§2.6), but gas/oil/ags plug-ins (gas-day calendar, parcel model, grade/quality) are explicitly deferred.
- UI/UX specification — covered by UX Spec V1.0/V1.1. This document specifies the **data contracts** the grid and boards consume (§13).

### 1.4 Reading guide

Functional rules are numbered **FR-nnn** for traceability into the technical spec and implementation prompts. Sub-rules use letter suffixes (FR-051a, FR-051b); rules added after initial numbering use hex-style extensions (FR-03A, FR-03B, FR-07A, FR-07B) to avoid renumbering existing references. Sizing derivations are shown in full (§15) per documentation standards. Worked examples use the reference deal defined in §2.7.

---

## 2. Foundational Principles

These principles are decisions, not aspirations. Every structure in this document is shaped by them; the technical spec must not violate them.

### 2.1 Entities vs. measures

**FR-001.** The model distinguishes **entities** (objects with identity and an independent lifecycle: trade-leg obligations, price expressions, market-data series) from **measures** (numbers identified only by their coordinates: net position per zone×portfolio×interval, MtM per position×interval).

**FR-002.** Only entities are stored as bitemporal source of truth. Measures are **derived**: computed from entities and reference data, materialized for performance where needed, and always rebuildable from the entities that produced them.

**FR-003.** The litmus test: if a candidate record has no identity other than its coordinate axes, it is a measure and must not be modeled as a versioned entity. If it has a lifecycle (booked, amended, novated, cancelled, restated), it is an entity.

Consequences applied throughout: the netted zone×portfolio position is a measure (Slot Cache, §10); the per-interval PPA price is a measure (Valuation, §9); the market/settlement price is a shared reference fact (Market Data, §8); the trade-leg obligation and the price expression are entities (§5, §6).

### 2.2 Grain follows the lifecycle of change

**FR-004.** The grain of a bitemporal store is the grain at which change events are born. For positions, change events (fills, amendments, novations, cancellations, backdated corrections) target **trade legs**; therefore the Position Ledger is grained at the trade-leg obligation, never at the delivery interval and never at an aggregation level.

**FR-005.** Interval-level fan-out and aggregation-level netting are projections performed downstream of the ledger (valuation layer, slot cache), never upstream in it.

### 2.3 Bitemporality

**FR-006.** Bitemporal structures track two independent time axes per version:

- **Valid time** (`valid_from`, `valid_to`): when the fact is true in the business world (effective dates of the obligation/version).
- **Knowledge time** (`known_from`, `known_to`): when the system held the version as truth. Append-only; versions are superseded by closing `known_to` and appending a new version, never by in-place update or delete.

**FR-007.** As-of reconstruction fixes both axes: "state for business date B as known at knowledge date K" ⇒ filter `valid_from ≤ B < valid_to AND known_from ≤ K < known_to`. Snapshots are **reconstructed by query, never stored** (with the single exception of the EOD struck mark, §9.4, which is a deliberately frozen derived artifact).

**FR-008.** Backdated corrections move knowledge time only (valid time unchanged); forward-effective events (e.g., unwind from date D) move valid time. Amendment records must carry a reason classification distinguishing the two.

**FR-009.** Bitemporality applies to: the Position Ledger, PriceExpression versions, market-data series versions, and settlement valuation cells. It explicitly does **not** apply to forward/MtM marks (§2.5, §9.3) or to the slot cache.

**FR-009a.** Bitemporality is **not replaceable** by the VolumeSeries BAV (Best Available Volume) mechanism. BAV answers "what will physically flow at a point" (operational); the bitemporal ledger answers "what was our booked risk as known at time T" (the REMIT/EMIR audit question). These are different questions on different time axes with different consumers, and conflating them would lose the regulatory-mandated ability to reconstruct historical risk state.

**FR-009b.** Bitemporality is **not replaceable** by denormalized position-interval-value snapshots. The snapshot alternative — storing a flat copy of `(trade, interval, position, price, value)` at each observation point — fails on four axes:

1. **Storage explosion.** The bitemporal ledger stores ~24 rows per deal-year × 2–5× amendment factor. A snapshot approach stores one row per interval per snapshot point: a 10-year PPA with 350K intervals/year and daily snapshots produces O(10⁹) rows per deal vs ~1,200 bitemporal rows. Forward marks (FR-075) compound this: every curve tick × every open interval would be persisted, adding O(10⁹) more rows per deal; the ephemeral classification eliminates these entirely.

2. **Backdated corrections are destructive.** A correction that changes quantity from 50 MW to 48 MW since inception requires either (a) rewriting all historical snapshots — destroying the original "we believed 50" fact and violating the regulatory reconstruction requirement (FR-007) — or (b) storing both old and new values at every snapshot point, which reinvents bitemporality with worse storage characteristics.

3. **Grain mismatch (D-1, FR-004).** Change events target trade legs, not intervals. Snapshotting at interval grain means every amendment rewrites O(intervals) rows instead of O(1) ledger versions. The bitemporal ledger's sparse grain matches the lifecycle grain; interval fan-out is a downstream projection (FR-005), not a source-of-truth concern.

4. **Snapshot-like access is provided by caches.** Where flat interval-grained reads are needed (trader grid, risk reports), the system provides rebuildable caches (S6 slot cache — §10; S6b trade interval cache — §10.4) and one deliberately frozen snapshot (S5c EOD struck mark — §9.4, FR-079). These give the same read performance without making redundant copies the source of truth. The caches are always rebuildable from the bitemporal entities.

This is a decided design choice, not an open question. The bitemporal approach stores **facts** (what changed, when we knew it); snapshots store **views** (what the world looked like at time T). Facts are compact and composable; views are redundant and brittle.

**FR-009c.** Bitemporality is **deliberately not applied** to volume intervals (`VolumeInterval`, `MeteredActualInterval`). Volume intervals use a lighter versioning model matched to their lifecycle:

- **PROFILE intervals** are immutable after creation. When a trade is amended, the old series version (with its intervals) is marked AMENDED and a new series version is created with new intervals. Both versions coexist. The `transaction_time` on the `VolumeSeries` header records when each version was created. Historical reconstruction ("what was the contractual volume on date T?") queries the series version with the highest `version_id` where `transaction_time ≤ T`.

- **FORECAST intervals** are replaced wholesale on re-forecast. Each update creates a new `VolumeSeries` version (new `version_id`, new `transaction_time`). Historical reconstruction uses the same series-header `transaction_time` filter.

- **METERED_ACTUAL intervals** use forward-link append-only versioning (`version` + `supersedes_id` on the interval row). Corrections append new intervals pointing to the superseded row. The chain itself is the audit trail.

**Why not bitemporal at the interval level:** Volume intervals are dense (350K rows/year per deal at 15-min granularity). Adding `valid_from/to` + `known_from/to` to each interval row would multiply storage by the amendment factor for no benefit — the series-header `transaction_time` already answers "which version was active at time T?" with O(versions) overhead instead of O(intervals × versions). The position ledger is sparse (~24 rows/year/deal), making bitemporality cheap there. The settlement cell's input-version-set (FR-056) records the volume `version_id` used, so reproducibility is achieved through the combination of ledger bitemporality + series version chain — not by making intervals bitemporal.

**FR-009d.** The amendment factor for volume series is empirically low, further justifying series-level over interval-level versioning. A typical 7–15 year PPA sees 5–15 total amendments over its contract life, distributed as:

| Amendment category | Expected frequency | Volume series impact |
|---|---|---|
| Counterparty / admin corrections (EIC fix, legal entity rename, portfolio reassignment) | 2–5× per contract | None — position ledger and/or expression only |
| Allocation changes (multiplier ramp-up, partial unwind, stepped allocation) | 1–3× per contract | VolumeReference updated (multiplier, effectiveFrom/To); underlying intervals unchanged |
| Price expression amendments (cap/floor renegotiation, index base year rebasing) | 1–2× per contract | None — expression only |
| Delivery window changes (extension, early termination, grid connection delay) | 0–1× per contract | New series version with new intervals |
| Novation (counterparty change via M&A, credit event) | 0–1× per contract | None — ledger only |
| Force majeure / regulatory reclassification | 0–1× per contract | Possibly new series version |

Crucially, most amendments (counterparty fixes, price changes, portfolio moves) **do not touch volume at all**. Allocation changes update `VolumeReference.multiplier` — the intervals stay the same. Only delivery window changes and trade restructurings produce new series versions. A realistic 10-year PPA has **2–3 volume series versions**, not dozens.

Storage comparison for a 10-year PPA (~3.5M intervals):
- **Series-version chain** (current design): 3 versions × 3.5M intervals = ~10.5M rows. Only the latest version is queried on the hot path.
- **Interval-level bitemporality**: same ~10.5M rows but each row is ~30% wider (4 extra TIMESTAMPTZ columns), and every query must filter on `known_from ≤ K < known_to` — adding complexity and index overhead to the highest-volume read path (`queryVolumeForTradeLeg`).

The 2–5× amendment factor cited throughout this spec derives from this distribution. It is conservative — most deals cluster around 3–4× total (original + 2–3 amendments); some are never amended; a few troubled contracts may see 10+.

**FR-009e.** Worked sizing example: 15-year wind PPA (asset WP-NORDSEE, 30% allocation, 15-min granularity, delivery 2026–2041) with 12 amendments over contract life. This example demonstrates why series-level versioning (FR-009c) is sufficient and interval-level bitemporality is unnecessary.

*Base interval count:*
- Normal year: 365 × 96 = 35,040 intervals. Leap year: 366 × 96 = 35,136. DST: net zero per year.
- 15 years (4 leap years): (11 × 35,040) + (4 × 35,136) = **525,984 intervals ≈ 526K** per full series version.

*Amendment-to-version mapping (12 amendments → 3 new PROFILE versions):*

| # | Year | Amendment | New PROFILE version? | Reason |
|---|---|---|---|---|
| 1 | 1 | Counterparty EIC correction | No | Ledger-only (FR-037) |
| 2 | 2 | Multiplier ramp-up 0.20 → 0.30 | No | VolumeReference.multiplier updated; same intervals |
| 3 | 3 | Price cap renegotiated | No | PriceExpression only (§6) |
| 4 | 4 | Portfolio reassignment | No | Ledger-only |
| 5 | 5 | Grid connection delay (delivery start shifts 6 months) | **Yes → v2** | ~509K intervals (14.5yr remaining) |
| 6 | 6 | Partial unwind of 10% allocation | No | New VolumeReference (multiplier 0.20, effectiveTo shortened) |
| 7 | 8 | Novation (counterparty M&A) | No | Ledger-only |
| 8 | 9 | HICP index base year rebased | No | PriceExpression only |
| 9 | 10 | Delivery extended 1 year (to 2042) | **Yes → v3** | ~561K intervals (adds ~35K) |
| 10 | 11 | Price floor removed | No | PriceExpression only |
| 11 | 13 | Force majeure curtails last 2 years | **Yes → v4** | ~491K intervals (delivery_end pulled back) |
| 12 | 14 | Counterparty legal entity rename | No | Ledger-only |

*PROFILE interval rows (per trade-leg):*

| Version | Trigger | Intervals | quality_state |
|---|---|---|---|
| v1 (original) | Trade capture | 526K | AMENDED (superseded by v2) |
| v2 (grid delay) | Delivery window shift | 509K | AMENDED (superseded by v3) |
| v3 (extension) | Delivery extended | 561K | AMENDED (superseded by v4) |
| v4 (curtailment) | Force majeure | 491K | EFFECTIVE (current) |
| **Total** | | **~2.09M** | Only v4 (491K) on hot path |

*FORECAST interval rows (per asset — shared, independent of trade amendments):*

Forecast refreshes are NWP-driven (Numerical Weather Prediction), not trade-driven. Refresh cadence varies by asset type and time horizon:

| Horizon | Solar asset | Wind asset |
|---|---|---|
| Near-term (M+0…M+2) | Daily (satellite + NWP model runs) | Daily–weekly (NWP) |
| Medium-term (M+3…M+12) | Weekly–monthly (P50/P75 yield) | Monthly (calibrated yield) |
| Far-term (M+13+) | Quarterly (TMY-based) | Quarterly (TMY-based) |

The CTRM stores the "best-available generation forecast for forward valuation" — typically the latest day-ahead or week-ahead forecast published by the asset operator. It does NOT store every intraday NWP run.

**Solar worst-case (daily near-term refresh):**

| Horizon | Versions/year | Intervals/version | Rows/year |
|---|---|---|---|
| Near-term (3 months materialized) | 365 | ~8,755 | ~3.20M |
| Medium-term (partial supersession, ~30% changed) | 12 | ~7,880 | ~0.09M |
| Far-term (few materialized) | 4 | ~2,000 | ~0.01M |
| **Total** | | | **~3.30M/year** |

Over 15 years: 3.30M × 15 = **~49.5M rows** (solar worst case).

**Wind typical (weekly near-term refresh):**

| Horizon | Versions/year | Intervals/version | Rows/year |
|---|---|---|---|
| Near-term | 52 | ~8,755 | ~0.46M |
| Medium-term | 12 | ~7,880 | ~0.09M |
| Far-term | 4 | ~2,000 | ~0.01M |
| **Total** | | | **~0.56M/year** |

Over 15 years: 0.56M × 15 = **~8.4M rows** (wind typical).

Only the latest version's intervals are queried for forward marks (FR-075). The FORECAST series is per asset and shared across all trade-legs on that asset — if 5 PPAs reference one solar asset, the 49.5M rows serve all 5 trades via VolumeReference × multiplier (D-11).

*METERED_ACTUAL interval rows (per asset — progressive accumulation):*

After 10 years of delivery: 10 × 35,040 = 350,400 base intervals. Quality transitions (PROVISIONAL → VALIDATED, ~5% ESTIMATED corrections) yield average version factor ~2.05. Total: **~718K rows**.

*Aggregate row count for this deal:*

| Layer | Table | Wind asset rows | Solar asset rows | Hot-path rows |
|---|---|---|---|---|
| PROFILE intervals | `volume_interval` | 2.09M | 2.09M | 491K (v4 only) |
| FORECAST intervals (per asset, shared) | `volume_interval` | 8.4M | 49.5M | ~8,755 (latest version) |
| METERED_ACTUAL (per asset, shared) | `metered_actual_interval` | 718K | 718K | Latest quality per interval |
| Series headers (all types) | `volume_series` + `metered_actual_volume_series` | ~860 | ~5,500 | 1–2 |
| VolumeReference | `volume_reference` | 2–3 | 2–3 | 1–2 (active allocations) |
| Position ledger (S1) | separate module | ~720 | ~720 | ~180 (current version per block) |
| **Total (intervals)** | | **~11.2M** | **~52.3M** | **~500K** |

The FORECAST rows dominate for solar assets but are **per asset, shared across all trades**. If 5 PPAs reference one solar asset, the 49.5M FORECAST rows serve all 5 trades. Per-trade exclusive storage is only ~2.09M (PROFILE).

*Cost of interval-level bitemporality (rejected alternative):*

Solar worst case (~52.3M interval rows per asset), with bitemporality:
- 4 additional TIMESTAMPTZ columns per row (`valid_from`, `valid_to`, `known_from`, `known_to`): +32 bytes/row = ~1.67 GB raw storage per asset.
- Composite index on `(tenant_id, series_key, interval_start, known_from, known_to)`: ~100 bytes/row = ~5.23 GB additional index per asset.
- Every hot-path query (`queryVolumeForTradeLeg`, FR-054) gains a mandatory filter: `AND known_from <= :asOf AND known_to > :asOf` — range predicate on the highest-frequency read path.
- At platform scale (200 tenants × ~5 solar assets each = ~1,000 solar assets): ~6.9 TB unnecessary index/column overhead.
- Benefit: **zero** — the series-header `transaction_time` + `version_id` already answers "which version was active at time T?" at O(versions) cost, not O(intervals).

The high FORECAST version count for solar assets (daily refresh) makes the FR-009c decision even more impactful than for wind. Series-level versioning provides equivalent audit capability with zero additional per-row overhead, simpler queries, and no bitemporal index on the interval tables.

**FR-009f.** Denormalized position snapshots — a flat `(trade_leg, interval, position_mw, volume, price, value, snapshot_date)` table capturing the full resolved state at each observation point — are rejected as an alternative to the bitemporal ledger + series-version chain. The storage and write amplification are prohibitive.

*Snapshot trigger analysis (same 15-year solar PPA reference deal):*

Any input change requires re-snapshotting the full interval set for all affected trades:

| Trigger | Frequency over 15 years | Intervals re-snapshotted |
|---|---|---|
| Trade amendment | 12 | 526K per trade |
| Forecast refresh (solar, daily near-term) | ~5,475 | 526K × all trades on asset |
| Meter data arrival (daily, delivered periods) | ~3,650 | 526K × all trades on asset |
| Price curve tick (daily EOD minimum) | ~5,475 | 526K per trade |
| **De-duplicated to daily EOD** | **~5,475 unique days** | **526K per trade per day** |

*Per-trade snapshot row count:*

At each snapshot point, the deal has ~526K intervals spanning the full delivery window. Daily EOD snapshots over 15 years:
- 526K intervals × 5,475 snapshot days = **~2.88 billion rows per trade**.

*Platform-scale comparison (200 tenants × 20 PPA trades = 4,000 trades):*

| Metric | Current design (bitemporal + version chain) | Denormalized snapshots |
|---|---|---|
| **Per-trade rows** | ~2.8M (720 ledger + 2.09M PROFILE intervals) | ~2.88B |
| **Per-asset rows (shared)** | ~50.2M (49.5M FORECAST + 718K METERED) | N/A — duplicated per trade |
| **Platform total** | ~60.8B rows | ~11.5 trillion rows |
| **Ratio** | 1× | **~189×** |
| **Daily write load (forecast refresh)** | ~8,755 rows (one series version, shared) | ~2.1B rows (526K × 4,000 trades) |
| **Storage after 1 year (Aurora @ $0.10/GB-month, ~200 bytes/row)** | ~1.1 GB → ~$0.11/month | ~143 TB → ~$14,300/month |

*Why the explosion is structural, not an implementation detail:*

1. **Interval × observation-point multiplication (FR-004 violation).** The snapshot grain is (trade, interval, snapshot_date) — three axes multiplied. The current design keeps position at trade-leg grain (FR-004), intervals at series-version grain (FR-009c), and observation-time on the series header. The three axes are **composed by query**, not **multiplied into rows**.

2. **No volume sharing (D-11 violation).** In the current design, a FORECAST series is stored once per asset and shared across all trades via `VolumeReference × multiplier`. In the snapshot approach, each trade gets its own copy of `forecast × multiplier` at each snapshot point. For 5 PPAs on one solar asset: 5× duplication of 49.5M rows = 247.5M redundant rows per asset.

3. **Write amplification on forecast refresh.** A single daily solar forecast update (one series version, ~8,755 intervals) forces re-snapshotting 526K intervals × every trade on the asset. The write amplification factor is `(intervals_per_trade / intervals_per_forecast_version) × trades_per_asset` = (526K / 8,755) × 5 = **~300×**.

4. **Backdated corrections are destructive.** A snapshot can be rewritten (losing the original fact, violating FR-007) or dual-stored (reinventing bitemporality with 2.88B rows as the base instead of 720). Neither is acceptable.

5. **Query cost inversion.** "Position as of date T" in the snapshot model requires scanning O(intervals) rows at the matching snapshot date — a full table scan with date filter. In the current design: O(1) bitemporal filter on the ledger (720 rows) + O(1) version lookup on the series header → then read the matching intervals. The heavy read (intervals) happens once per version, not once per observation point.

*Structural query cost comparison (no production data exists — system is in design phase):*

The timings below are **not benchmarked**. They are structural estimates based on PostgreSQL B-tree index depth characteristics on Aurora. They illustrate the architectural difference in index working set and scan path, not a performance guarantee. Design-target SLAs are in V2.0 §14.

| Query | Current design | Snapshot approach | Structural difference |
|---|---|---|---|
| **`queryVolumeForTradeLeg` (1 day, 96 intervals)** — the hot-path query (FR-054) | 3-join: `volume_reference` (1 row, index over ~5K) → `volume_series` (1 row, index over ~5.5K) → `volume_interval` (96 rows, range scan). Total: 98 rows touched. B-tree depth: 2–3 levels per lookup. Design target: p95 = 15 ms. | Index lookup into snapshot table: 96 rows, but index is over **2.88B rows** (15-year deal). B-tree depth: 6–7 levels. | Index 3–4 levels deeper; working set unlikely to fit in buffer cache. Estimated 3–5× slower per lookup. |
| **Regulatory as-of ("position on July 1")** — audit query (FR-007, FR-115) | Bitemporal filter on `position_ledger` (~720 rows) → version lookup on `volume_series` header (~5.5K rows) → full interval scan (526K rows). Ledger + version lookup: O(log n) on small tables. | Scan snapshot table for `snapshot_date = July 1, trade = T-7788` → 526K rows from a 2.88B-row table. Same interval count returned, but index lookup into a 2.88B-row table vs 720 + 5.5K-row tables. On warm/cold data (audit queries), snapshot index pages require disk I/O that the current design avoids. | Same result set; index entry point is ~6 orders of magnitude larger in the snapshot table. |
| **Grid display (1 zone, 1 day)** — trader's primary screen | Direct read from S6/S6b cache (pre-built, 96 rows, no joins). Design target: p95 = 5 ms (S6b). | Snapshot table IS the cache — 11.5T rows platform-wide. No separation between hot-path and audit reads. Every grid query competes with audit scans. | Current design isolates hot-path reads in a purpose-built ~29M-row cache; snapshot approach merges hot and cold data. |

**Important:** Actual performance depends on Aurora instance size, buffer pool hit rates, concurrent load, and query plan choices. The structural comparison demonstrates that the current design keeps hot-path index lookups over tables of thousands to millions of rows, while the snapshot approach forces the same lookups over tables of billions of rows — a qualitative difference in B-tree depth and cache residency that no amount of tuning can bridge.

The current design achieves the same as-of query capability by composing the bitemporal ledger's `known_from/known_to` filter (FR-007) with the volume series' `transaction_time` + `version_id` chain (FR-009c). Two lightweight mechanisms that together replace trillions of redundant snapshot rows.

### 2.3a Regulatory Reporting Alignment

**FR-009g.** The bitemporal position ledger at trade-leg × delivery-month grain (D-1) is specifically designed to match the grain at which EU energy regulators request data. No applicable regulation requires interval-level bitemporality. This section documents the regulatory basis for the grain and versioning decisions in FR-004, FR-009c, and D-1.

*Applicable regulatory framework:*

| Regulation | Scope | Reporting grain | Frequency | System mapping |
|---|---|---|---|---|
| **REMIT** (EU 1227/2011) | Wholesale energy market integrity | **Trade-level**: parties, product, price, quantity, delivery period, execution venue | T+1 to ACER | Trade entity (external to S1/S3) |
| **REMIT Art. 8** (market surveillance) | Position reconstruction on request | **Position-level**: net position by delivery period, counterparty, product, at any historical knowledge-date | Ad hoc (ACER investigation) | S1 bitemporal filter: `known_from ≤ K < known_to` (FR-007) |
| **EMIR** (EU 648/2012) | OTC derivative clearing & reporting | **Trade-level** + **daily mark-to-market** per trade | T+1 (trade), daily (valuation) | Trade entity + S5c EOD struck mark (position × month-bucket × business day) |
| **MiFID II / MiFIR** | Transaction reporting & position limits | **Trade-level** (T+1); **aggregated net position** per commodity derivative (daily to NCA, weekly public) | T+1 / daily / weekly | Trade entity + S6/S7 rollups from ledger |
| **MAR** (EU 596/2014) | Market abuse prevention | **Reconstruct-on-demand**: full trading book state at any historical timestamp | Ad hoc (investigation) | S1 bitemporal filter at knowledge-time K |
| **GDPR** | Data retention | **Retention ceiling**: delete after regulatory period | Ongoing | pg_partman partition drop (V2.0 §9); 7-year ceiling |

*Regulatory query-to-system mapping:*

| Regulatory question | FR reference | System path | Grain | Rows (15-year PPA) |
|---|---|---|---|---|
| "Report all trades executed today" (REMIT T+1) | — (trade module) | Trade capture entity | 1 per trade | 1 |
| "Mark-to-market of each position" (EMIR daily) | FR-079 | S5c: struck mark per position × month-bucket × business day | Monthly bucket | ~180 |
| "Reconstruct position at date K" (REMIT Art. 8 / MAR) | FR-007 | S1: `valid_from ≤ B < valid_to AND known_from ≤ K < known_to` | Trade-leg × delivery-month | ~720 |
| "Reproduce settlement for delivery month M" (audit) | FR-072, FR-056 | S5a settlement cell → input-version-set → volume `version_id` → intervals | Interval-level (internal) | ~2,976 per month |
| "Aggregate net position per contract" (MiFID II limits) | FR-085 | S6/S7 rollup from ledger, or re-aggregate on demand | Monthly aggregate | ~180 |
| "Full book state at timestamp T" (MAR investigation) | FR-007, FR-115 | S1 bitemporal filter at knowledge-time T across all active trades | Trade-leg × delivery-month | O(active trades × months) |

**FR-009h.** Interval-level data (the 526K quarter-hours in a 15-year PPA) is **operationally necessary but not regulatory-facing**. It serves four internal purposes:

1. **Computing** settlement values: interval × price → value, aggregated into the settlement cell (S5a) that IS part of the regulatory record (FR-072).
2. **Resolving** forward marks: forecast × multiplier → per-interval exposure, rolled up into the EOD struck mark (S5c) that IS the EMIR-reportable valuation (FR-079).
3. **Displaying** the trader grid: slot cache (S6) materialization — a convenience view, never reported to regulators.
4. **Reconciling** TSO meter data against traded positions — operational, not regulatory.

The regulatory output is always aggregated: a trade report, a monthly mark, a net position. Intervals are the computation ingredients consumed behind the aggregation boundary. No regulator queries intervals directly.

**FR-009i.** This regulatory alignment confirms and reinforces the following decided design choices:

- **D-1 (ledger grain = trade-leg × delivery-month):** Matches REMIT Art. 8 and MAR reconstruction grain exactly. Regulators ask "what was the position for delivery month M?" — one ledger block per month, not 2,976 intervals.
- **FR-009c (no interval-level bitemporality):** Regulators never query volume intervals with a knowledge-time filter. When reproducing a settlement (FR-056), the `version_id` in the input-version-set points to the correct volume series version — the series-level version chain is sufficient.
- **FR-075 (forward marks ephemeral):** No regulator asks "what was your intraday mark at 14:32?" — that question has no regulatory standing. The EMIR daily valuation is answered by S5c (EOD struck mark at monthly-bucket grain), not by persisting every intraday tick.
- **FR-079 (S5c = one sanctioned snapshot):** EMIR daily valuation IS a genuine snapshot requirement, but at position × month-bucket × business day grain — not at interval grain. S5c delivers this with ~3,420 rows per 15-year deal (180 months × 19 monthly reporting days average), not billions.

### 2.4 Sparse ledger, dense derivations, generated density

**FR-010.** The ledger stores obligations sparsely at native/block grain (a flat annual obligation is O(months) of rows, not O(intervals)). Interval-dense data exists only in derived layers (settlement valuation as delivery progresses; slot cache over the hot window) and in shared reference stores (market data, volume series).

**FR-011.** Display density (a grid cell for every interval, including empty ones) is **generated at read time** by projecting sparse/derived data onto a calendar-generated grid. Empty cells are never stored.

### 2.5 Durable vs. ephemeral valuation

**FR-012.** Valuation values are classified by auditability:

- **Realized settlement values** (delivered intervals, priced off published fixings): durable, bitemporal, reproducible (§9.2).
- **EOD struck marks** (official close-of-business MtM): durable, immutable per strike, aggregated per position × delivery-month bucket per business day (§9.4).
- **Intraday forward/MtM marks**: **ephemeral** — current-state only, overwritten on revaluation, held in cache; never persisted as bitemporal history (§9.3).

This classification is the primary control preventing valuation-store explosion (§15.4) and is non-negotiable for the technical design.

### 2.6 Commodity-neutral core, power plug-in

**FR-013.** The Position Ledger, bitemporal machinery, delivery-point hierarchy, delivery ranges, PriceExpression, the derived-measure layering, and the unified `VolumeReference → VolumeSeries × multiplier` resolution (D-11) are commodity-neutral. Power-specific machinery — dense interval grid, MW/MWh duality, peak/off-peak, cascading — is modeled as the power profile of the platform, kept out of the neutral core so that gas (offset gas-day, basis pricing) and bulk commodities (parcel/lot, grade, formula differentials) can be added as profiles without core migration. No speculative gas/oil structures are built now.

**FR-013a.** The `VolumeReference × multiplier` pattern is not power-specific — it models any scenario where multiple economic interests slice a shared physical asset or pool. The following commodity extensions demonstrate that the unified resolution pattern, VolumeReference, and S6b (trade interval cache) require **zero structural changes** to the core; only interval semantics, unit duality, and delivery-point types change:

| Commodity | Shared asset | VolumeReference multiplier | Interval grain | Unit duality | PriceExpression extensions |
|---|---|---|---|---|---|
| **Power** (current) | Wind/solar farm | Trade's share of asset output (0.30) | 15-min / 5-min / 1-min | MW / MWh | Collar, neg-price gate, HICP escalation, FX |
| **Gas** | Storage facility, pipeline receipt point | Withdrawal/injection right as fraction of facility capacity (0.25) | Gas-day (06:00–06:00 CET) or hourly | kWh/h / kWh (or therms) | Basis differential, seasonal swing, take-or-pay floor |
| **Oil (crude/products)** | Loading terminal, refinery output | Consortium participant's share of term contract (0.40) | Monthly cargo windows or daily nominations | bbl/day / bbl (or MT) | Dated Brent + quality diff + freight + demurrage |
| **Ags (grain, softs)** | Cooperative pool, warehouse receipt | Farmer/participant share of pool contract (0.15) | Crop-month delivery windows | MT/period / MT (or bushels) | CBOT/Euronext front-month + basis + grade premium/discount |

**FR-013b.** Worked commodity extension examples (for illustration; not built now):

**Gas — storage withdrawal rights:**
A gas storage facility (e.g., "Rehden", 4,400 GWh working volume) is modeled as an asset. Its withdrawal capacity forecast is a `VolumeSeries(seriesType=FORECAST)`. Three traders hold withdrawal rights:

- Trade T-9100: 25% of facility capacity → `VolumeReference(multiplier=0.25, volumeSeriesKey=FCST-REHDEN)`
- Trade T-9200: 15% → `VolumeReference(multiplier=0.15, volumeSeriesKey=FCST-REHDEN)`
- Resolution: `facility_forecast_interval.volume × 0.25` — identical code path to power PPAs

Plug-in changes: MarketCalendar returns gas-day intervals (06:00–06:00 CET instead of midnight-midnight); `volume_unit` extends to `KWH_PER_HOUR`; DeliveryPoint hierarchy adds `VirtualTradingPoint` (e.g., THE/TTF). Core resolution, S6b cache, dependency index, settlement cells — all unchanged.

**Oil — consortium lifting:**
A term purchase contract ("Bonny-Light-2027", 120,000 bbl/day for 12 months) is modeled with a `VolumeSeries(seriesType=PROFILE)` containing monthly lifting allocations. Three consortium participants:

- Participant A: 40% → `VolumeReference(multiplier=0.40)`
- Participant B: 35% → `VolumeReference(multiplier=0.35)`
- Participant C: 25% → `VolumeReference(multiplier=0.25)`
- Resolution: `lifting_schedule_interval.volume × 0.40` — same pattern

Plug-in changes: `volume_unit` extends to `BBL_PER_DAY` / `BBL`; intervals are monthly cargo windows (not 15-min slots); DeliveryPoint adds `LoadingTerminal`; PriceExpression adds differential + freight operators. Core unchanged.

**Ags — cooperative pool:**
A cooperative export contract ("Wheat-Export-Q4-2027", 10,000 MT) is modeled with a `VolumeSeries(seriesType=PROFILE)` containing crop-month allocations. Participating farms:

- Farm A: 15% → `VolumeReference(multiplier=0.15)`
- Farm B: 20% → `VolumeReference(multiplier=0.20)`
- Farm C: 30% → `VolumeReference(multiplier=0.30)`
- Resolution: `pool_allocation_interval.volume × 0.15` — same pattern

Plug-in changes: `volume_unit` extends to `MT_PER_PERIOD` / `MT`; intervals are crop-month windows; DeliveryPoint adds `Warehouse` / `SiloPoint`; PriceExpression adds basis + grade quality adjustment operators. Core unchanged.

**FR-013c.** The two cheap-now decisions that enable these future commodity profiles without core migration: (1) `price_expression_ref` instead of a scalar price column (PriceExpression tree is extensible with new operator types per commodity), (2) `VolumeReference` with `multiplier` instead of per-trade volume copies (the shared-asset-slicing pattern is universal). A third decision — polymorphic `DeliveryPoint` hierarchy (FR-022) — ensures delivery-location semantics extend without schema migration.

### 2.7 Reference deal (used in examples throughout)

| Attribute | Value |
|---|---|
| Trade | `T-7788`, tenant `TN_0042`, leg 1 |
| Instrument | EPEX DE_LU pay-as-produced wind PPA, offtaker (buy) side |
| Delivery | 2026-08-01 00:00 → 2027-08-01 00:00 Europe/Berlin, settled at 15-min |
| Volume | Pay-as-produced → volume series `VS-3312` (forecast + metered actual) |
| Price | `PXE-9001`: collar (floor 42.00 / cap 95.00 EUR/MWh at 2025 base) escalated by HICP; negative-price zeroing overriding the floor; FX leaf (degenerate EUR→EUR) |
| Interval count | 35,040 QH: 363 normal days ×96, plus 100 (2026-10-25 fall-back) and 92 (2027-03-28 spring-forward) |

---

## 3. Reference & Calendar Structures

The position/valuation model depends on four reference structures. Their internal administration is out of scope; their **contracts** are in scope because valuation correctness depends on them.

### 3.1 Market

**FR-020.** A `Market` is a first-class reference entity (e.g., EPEX_SPOT, EEX, NORD_POOL; later PJM, ERCOT). It owns: market timezone; granularity floor (15-min for EPEX DE_LU; extensible to 5-min/1-min per market); trading/holiday calendar; peak/off-peak definition; gate-closure rules; venue identifiers (MIC) for regulatory reporting. Execution venue is captured by reference to `Market`, never by enum.

**FR-021.** The former `Source` enum (DB/FILL) is abolished. Its two conflated meanings are re-homed: staging lifecycle (`stagingState`: STAGING → CONFIRMED) lives on the capture pipeline; position origin is `originType` ∈ {EXCHANGE_FILL, BILATERAL_TRADE, INTERNAL_TRANSFER, MANUAL_POSITION_ADJ} on the ledger. Nomination/schedule/forecast/actual/imbalance are **not** position origins; they are volume-series layers (§7).

### 3.2 DeliveryPoint hierarchy

**FR-022.** `DeliveryPoint` is a polymorphic hierarchy: BiddingZone (EU), Hub / Zone / Node (US, future), MeteringPoint (physical, EIC-W), VirtualPoint (financial). Every delivery point references its `Market`. Positions reference the **finest applicable** delivery point; aggregation rolls up the hierarchy.

**FR-023.** Netting semantics are boundary-aware: netting within a bidding zone is physically meaningful; netting across bidding zones (or across US nodes/hubs) is financial only and requires transmission-rights context. Aggregations that cross a physical-netting boundary must be flagged as financial nets in any consuming view or report.

### 3.3 MarketCalendar

**FR-024.** The MarketCalendar service is the **sole authority** for the interval structure of any delivery period in a market: given (market, date range, granularity), it returns the ordered list of atomic intervals in market-local wall-clock, with DST handled by construction — 96 QH on normal EU days, 100 on the fall-back day, 92 on the spring-forward day — including unambiguous disambiguation of the duplicated fall-back hour.

**FR-025.** No component may derive interval counts or boundaries by timestamp arithmetic (`start + n×15min`). All grid generation, fan-out, roll-up, and cascade expansion consume the MarketCalendar.

### 3.4 PeakCalendar

**FR-026.** Peak/off-peak is a deterministic function of (market, interval local wall-clock, holiday calendar). It is **never stored on position rows**. It is materialized once per (market, atomic interval) into the interval dimension consumed by the slot cache and rollups (§10, §11), versioned by `calendar_version` so that holiday-calendar revisions re-materialize affected intervals identifiably.

**FR-027.** Peak classification is independent of write time, gate closure, and snapshot timing. (Gate closure is a separate market rule governing tradability/nominability windows and has no effect on peak flags.)

---

## 4. Structure Overview

| # | Structure | Kind | Grain | Temporality | Density | Persistence |
|---|---|---|---|---|---|---|
| S1 | Position Ledger | Entity (source of truth) | Trade-leg obligation × delivery-month block | Bitemporal | Sparse | Durable, append-only |
| S2 | PriceExpression | Entity | Expression version per trade leg | Bitemporal (with trade lifecycle) | Sparse | Durable, append-only |
| S3 | Volume Series (interface) | Entity (external module) | Series × atomic interval, supersession-versioned | Versioned (supersession per series: VolumeSeries FORECAST/PROFILE, MeteredActualVolumeSeries) | Dense per series per delivered/forecast range | Owned by VolumeSeries module (V3.0); consumed via VolumeReference × multiplier (§7) |
| S4 | Market Data Store | Shared reference facts | Series × interval × as-of | As-of / bitemporal per series | Dense per series | Durable |
| S5a | Settlement valuation cells | Measure (derived) | Position × atomic interval | Bitemporal | Dense over delivered past only | Durable |
| S5b | Forward/MtM marks | Measure (derived) | Position × atomic interval | None (current-state) | Dense over open future | **Ephemeral** (cache + overwrite) |
| S5c | EOD struck marks | Measure (frozen projection) | Position × delivery-month bucket × business day | Uni-temporal (as-of strike) | Sparse | Durable, immutable |
| S6 | Slot Cache | Measure (materialization) | (deliveryPoint × portfolio × positionType) × atomic interval | None (version-hashed current state) | Dense over hot window only | Rebuildable cache |
| S6b | Trade Interval Cache | Measure (materialization, **optional**) | Trade-leg × atomic interval | None (version-hashed current state) | Dense over hot window, per trade | Rebuildable cache (opt-in) |
| S7 | Rollup aggregates | Measure (materialization) | Aggregation level × period (hour/day/month, peak split) | None | Dense at coarse grain | Rebuildable |
| S8 | Dependency index | Infrastructure | (input series → valuation cell) edges | None | Proportional to open exposure | Rebuildable |

The dependency direction is strictly one-way: S1/S2 (+S3, S4 as inputs) ⇒ S5 ⇒ S6/S6b/S7. Any derived structure can be dropped and rebuilt from the structures to its left. The technical spec must preserve this rebuildability.

---

## 5. S1 — Position Ledger

### 5.1 Definition

The Position Ledger is the bitemporal, append-only source of truth for **trade-derived contractual exposure**. One logical position = the economic obligation of one trade leg over one delivery-month block. It carries no interval data, no market prices, no netting, and no operational volume layers.

### 5.2 Grain and decomposition

**FR-030.** A trade leg's delivery period is decomposed into **delivery-month blocks**: one ledger row (per version) per calendar month of delivery in the market timezone. The reference deal (Aug-2026…Jul-2027) yields 12 blocks, `POS-5501…POS-5512`.

**FR-031.** Rationale (binding on the tech spec): (a) month is the partition-alignment grain — each block lives wholly in one delivery-month partition, so delivery-window queries prune cleanly; (b) month is the natural cascade level for EU curve products; (c) finer decomposition (intervals) multiplies rows by 4–5 orders of magnitude with zero attribution benefit, since the atom of record remains the trade; coarser (single row per leg) defeats partition pruning for mid-period queries.

**FR-032.** Within a block, all position-own attributes are constant: direction/sign, delivery point, price-expression reference, currency, volume-unit semantics, portfolio/book attribution. If a trade's own terms genuinely change mid-month (rare; e.g., novation effective mid-month), the affected block is versioned with valid-time splitting, not sub-divided into new blocks.

**FR-033.** Shape within the block (time-varying MW) is **delegated by reference**: `quantity` semantics are either a fixed rate/volume for flat blocks or a reference to the contractual volume series (`VS-…`) for shaped/pay-as-produced deals. The ledger never fans shape into rows.

### 5.3 Attributes (functional definition)

| Attribute | Meaning | Rules |
|---|---|---|
| `tenant_id` | Tenant isolation key; partition-leading, present on every row | FR-120/122; no query may omit this filter |
| `position_id`, `version` | Identity of the block; monotonically increasing version | FR-006/007 versioning |
| `origin_type` | EXCHANGE_FILL / BILATERAL_TRADE / INTERNAL_TRANSFER / MANUAL_POSITION_ADJ | FR-021 |
| `trade_id`, `leg_id`, `block_seq` | Source trade linkage (typed, not free-text) | Mandatory; drill-down & reg reporting depend on it |
| `delivery_point` | Finest applicable point in hierarchy | FR-022 |
| `market` | Derived via delivery point; not duplicated as enum | FR-020 |
| `portfolio`, `book`, `strategy`, `counterparty` | Attribution dimensions for rollups | All aggregations derive from these; none are baked into grain |
| `product_code` | Instrument reference (product master) | Required for recon & reporting |
| `cascade_parent_id`, `cascade_generation` | Cascade lineage (Cal→Q→M→…) | §5.6 |
| `signed quantity` / `quantity_ref` | Signed rate (long +, short −) for flat blocks; volume-series reference for shaped | FR-034/035 |
| `volume_unit` | MW_CAPACITY or MWH_PER_PERIOD | FR-035 |
| `price_expression_ref` | Reference to S2 | FR-040; fixed price = degenerate expression |
| `currency` | Settlement currency of the leg | FX conversion is an expression concern (§6) |
| `delivery_range` | Half-open range [block start, block end) in market-local semantics | FR-036 |
| `native_granularity` | Tenor as traded (YEAR/QUARTER/MONTH/DAY/HOUR/QH…) | Attribute, not grain multiplier |
| `valid_from/to`, `known_from/to` | Bitemporal axes | §2.3 |
| `status` | ACTIVE / SUPERSEDED / CANCELLED (+ staging states upstream) | Terminal CANCELLED closes valid time |
| `amendment_reason` | Classified reason incl. backdated vs forward-effective | FR-008 |

**FR-034.** Quantity is **signed**: positive long, negative short. There is no direction enum on the ledger; LONG/SHORT is a projection for display. Net aggregation is a plain sum of signed quantities (unit-aware per FR-035).

**FR-035.** `volume_unit` semantics are binding on every aggregation: MW_CAPACITY averages over time (time-weighted where interval lengths differ) and replicates on fan-out; MWH_PER_PERIOD sums on roll-up and distributes on fan-out. No consumer may sum MW or replicate MWh.

**FR-036.** `delivery_range` is half-open `[start, end)` and is authored in market-local wall-clock semantics; interval materialization against it always goes through the MarketCalendar (FR-025).

### 5.4 Lifecycle & bitemporal behavior

**FR-037.** Position versions are produced exclusively by trade-lifecycle events. The position inherits its temporal axes from the triggering event: `known_from` = processing time of the amendment; `valid_from` = the amendment's business-effective date (which may be backdated). Trade events must therefore carry both dates.

Worked example (reference deal): counterparty EIC correction processed 2026-09-05, effective from inception. Every block gains v2 with `valid_from` unchanged (block start) and `known_from = 2026-09-05`; v1 rows get `known_to = 2026-09-05`. A later unwind effective 2027-02-01 closes `valid_to` of open blocks at that date and appends CANCELLED rows valid from it — a valid-time move, contrasting with the knowledge-time-only correction.

**FR-038.** Cancellation semantics: forward unwind closes valid time from the effective date (the obligation *was* real before it); void-ab-initio (booked in error) supersedes with a version whose status is CANCELLED across the whole valid range, preserving the erroneous version in knowledge history.

### 5.5 What the ledger explicitly excludes

**FR-039.** The ledger contains no: netted quantities; interval fan-out; market/settlement prices; MtM; peak flags; operational volume layers (nomination/schedule/forecast/actual/imbalance — these are volume series, §7); valuation state; staging lifecycle state (`stagingState` lives on the capture pipeline upstream of the ledger — only confirmed positions with `originType` enter S1). Any requirement to see those is satisfied by derived layers.

### 5.6 Cascading

**FR-03A.** Traded coarse products (e.g., EEX Cal base) cascade on ingestion into finer ledger blocks per exchange cascade rules, carrying `cascade_parent_id`/`cascade_generation` so lineage is reversible for audit. Rolling cascade (as the calendar approaches delivery) is a batch operation (§12.3) producing new versions with full bitemporal history. Cascade never changes economics — the sum of children equals the parent obligation.

**FR-03B.** Multi-level cascade is supported: a cascade child may itself be a cascade parent (Cal→Q→M, `cascade_generation` 0→1→2). Lineage traversal both upward (child → parent via `cascade_parent_id`) and downward (parent → children by querying `cascade_parent_id`) must be efficient. Partial unwind of cascaded children (e.g., sell 2 months of a quarter, keep 1) is handled by valid-time closing of individual child blocks per FR-037/038 — the parent is not modified.

### 5.7 Worked bitemporal example (7-day sequence)

Demonstrates that the same business date returns different answers as knowledge time advances — the core property the audit path (§13.4) relies on.

| Day | Event | Ledger effect | "Position for Aug-2026 block as of…" |
|---|---|---|---|
| D1 (Mon) | Trade T-7788 booked, 50 MW long | POS-5501 v1: qty=+50, valid_from=Aug-01, known_from=D1 | As of D1: **+50 MW** |
| D3 (Wed) | Amendment: qty corrected to 48 MW (backdated to inception) | v1 closed: known_to=D3. POS-5501 v2: qty=+48, valid_from=Aug-01, known_from=D3 | As of D1: +50 (v1 was truth then). As of D3: **+48** |
| D5 (Fri) | Counterparty EIC correction (no qty change) | v2 closed: known_to=D5. POS-5501 v3: qty=+48, counterparty updated, known_from=D5 | As of D1: +50. As of D3: +48 (old cpty). As of D5: **+48 (new cpty)** |
| D7 (Sun) | Partial unwind: 20 MW short effective D7 onward | v3 closed: valid_to=D7, known_to=D7. POS-5501 v4: qty=+28, valid_from=D7, known_from=D7 | As of D5 for business date D8: +48 (unwind not yet known). As of D7 for D8: **+28** |

4 events → 4 versions → the same Aug-2026 block answers 4 different quantities depending on the knowledge-date axis. This is why BAV (which has no knowledge-time dimension) cannot replace the bitemporal ledger for regulatory reconstruction (FR-009a).

---

## 6. S2 — PriceExpression

### 6.1 Definition

**FR-040.** Every position references a `PriceExpression` — a versioned entity representing the **recipe** for the leg's price. A fixed-price deal is the degenerate expression (single constant leaf). There is no scalar `price` column as source of truth for float/indexed deals; for such deals a per-interval price does not exist until resolved (§9).

### 6.2 Structure

**FR-041.** An expression is a typed tree:

- **Leaves — market-data references**: series key (S4), quotation/averaging window, fixing calendar, lag, unit; and volume references (metered/forecast series) where the amount formula needs them.
- **Leaves — constants**: contract constants (floor₀, cap₀, base index values, fixed differentials, fees).
- **Nodes — operators**: arithmetic (+ − × ÷); bound selection (min/max ⇒ floor/cap/collar); conditionals (e.g., negative-price zeroing); time aggregation over quotation windows; indexation/escalation (index ratio); FX conversion.
- **Root**: the per-interval settlement price and/or amount.

**FR-042.** Operator semantics must be explicit about **precedence between clauses**. Reference deal: negative-price zeroing *overrides* the floor (DA < 0 ⇒ 0, floor not applied). This is contract law, not a math convenience; the expression must encode clause precedence, and the tech spec must not hard-code a single precedence.

**FR-042a.** The negative-price conditional must support **both** market-standard variants: (a) zeroing (DA < 0 ⇒ price = 0, as in the reference deal), and (b) pass-through (DA < 0 ⇒ price = DA, offtaker compensates generator for curtailment avoidance — standard in post-2024 German PPAs). Both are conditional operators with different payoff branches; the expression tree encodes which variant applies per contract. The v1 operator set (O-3) must include both.

**FR-043.** Escalation references are dated: the reference deal uses HICP ref-month 2025-11 for delivery year 2026 and 2026-11 for delivery year 2027; the expression version defines the mapping delivery-period → reference observation. A deal crossing an escalation reset boundary resolves different factors on either side of it.

### 6.3 Versioning

**FR-044.** Expression **definitions** version bitemporally with the trade lifecycle (an amendment to the cap creates a new expression version, valid/known per FR-006). Expression **input values** version with the market-data series (§8). The two clocks are independent; every resolved valuation cell records both (§9.2).

### 6.4 Resolution contract

**FR-045.** Resolution of an expression for an interval: walk the tree; resolve each market-data leaf at the correct series version for the required as-of; apply operators; emit (value, `active_leaves`, full input-version set).

**FR-046.** `active_leaves` — the set of leaves that actually determined the output (e.g., collar inside ⇒ CPI referenced-but-inactive; negative-gated ⇒ only DA active) — is a **mandatory output of first resolution**. It is the basis for restatement blast-radius reduction (§12.2) and cannot be reconstructed cheaply after the fact.

**FR-047.** Resolution is a pure function of (expression version, input versions, interval). Same inputs ⇒ same output, always — this purity underwrites idempotent recomputation and replay safety (§12.4).

---

## 7. S3 — Volume Series Interface

The VolumeSeries module (Data Architecture V2.0) owns operational volume layers and their internal lifecycle (supersession, provisional→validated progression). This section specifies **only the consumption contract** the position/valuation model relies on — what it reads, how it reads it, and what events it reacts to. Module internals remain out of scope (§1.3).

### 7.1 Boundary: positions vs volume series

**FR-050.** A position is trade-derived economic exposure (price-bearing, signed, attributed); a volume series is the operational MW/MWh shape at a point/asset over time. NOMINATION, SCHEDULE, FORECAST, METERED_ACTUAL, IMBALANCE are volume-series layers and never appear as position rows or position types.

### 7.2 Series layers consumed and their purpose

**FR-051.** The position/valuation model consumes volume data via the unified V3.0 resolution pattern (D-11): every trade resolves volume through `VolumeReference → VolumeSeries × multiplier`. Three volume sources serve distinct valuation purposes:

| Source | Series type / entity | Consumed by | Purpose | Availability |
|---|---|---|---|---|
| **PROFILE** (VolumeSeries, `seriesType=PROFILE`) | Per trade-leg, dedicated | Slot cache fan-out (§10), trade interval cache (§10.4), settlement for fixed-profile trades (DA/bilateral) | Shape of the obligation — the MW per interval the trade commits to deliver/receive. For flat-block deals the ledger's fixed `quantity` suffices; for shaped-but-firm deals (profiled offtake) and DA fills, this is the per-trade volume series with `multiplier=1.0`. | Created at trade booking; constant unless the trade is amended. |
| **FORECAST** (VolumeSeries, `seriesType=FORECAST`) | Per asset, shared | Forward valuation (S5b, §9.3), EOD strike (S5c, §9.4), trade interval cache (§10.4) | Expected generation/consumption for **undelivered** intervals — the volume assumption behind forward marks. Shared across all trades on the asset; each trade's share = `forecast × multiplier`. | Available from asset onboarding; updated periodically (weather model refresh, re-forecast). |
| **METERED_ACTUAL** (MeteredActualVolumeSeries) | Per asset, separate entity | Settlement valuation (S5a, §9.2), trade interval cache (§10.4) | Delivered volume as measured — the volume fact behind settlement cells. Shared across all trades on the asset; each trade's share = `metered × multiplier`. Progresses through provisional→validated→estimated states. | Arrives D+1 or later; superseded as validated data replaces provisional. |

**FR-051a.** The position ledger references a volume series by `quantity_ref` (the `volumeSeriesKey` from VolumeReference, denormalized for convenience). The valuation layer resolves which **source** to read based on valuation type, interval delivery status, and whether the trade has a `meteredSeriesKey`:

- **Delivered interval, settlement cell (S5a), asset-linked trade (PPA):** read `VolumeReference.meteredSeriesKey` → MeteredActualVolumeSeries × multiplier. If metered data is not yet available for a delivered interval, the cell is not created — settlement is data-driven, never assumption-driven.
- **Delivered interval, settlement cell (S5a), fixed-profile trade (DA/bilateral):** `meteredSeriesKey` is null by design — these trades have no physical meter. Settlement reads `VolumeReference.volumeSeriesKey` → VolumeSeries(PROFILE) × 1.0. The traded volume IS the settled volume; this is not a "fallback" but the designed resolution path for trades without metering.
- **Undelivered interval, forward mark (S5b):** read `VolumeReference.volumeSeriesKey` → VolumeSeries(FORECAST) × multiplier for asset-linked trades; → VolumeSeries(PROFILE) × 1.0 for fixed-profile trades. If no forecast exists, the interval is marked with zero volume and flagged `VOLUME_MISSING` (visible in grid as a data-quality indicator, not silently zero-valued).
- **Fan-out for slot cache / grid display:** read VolumeSeries(PROFILE) for shaped/profiled deals; use the ledger's fixed `quantity` for flat-block deals.

**FR-051b.** The no-mixing rule is explicit and strict: there is **no automatic fallback** from one volume source to another for the same trade type. Specifically: for asset-linked trades (PPAs), if metered data is not yet available, the system does NOT substitute the forecast for settlement — it produces an explicit gap. Each source answers a different question (what is the obligation shape, what is expected, what was measured), and blending them silently would corrupt the realized/unrealized classification (FR-07A). A missing source produces an explicit gap, not a substituted value. Note: fixed-profile trades reading their PROFILE series for settlement is not a fallback — it is their designed resolution path (they have no meter by definition).

**FR-053.** The trade's MW shape legitimately exists in two projections — risk (position/valuation) and operations (BAV/nominations). They share sources (same trade, same series) but are materialized independently; neither is derived from the other's materialization. This means a forecast update flows to both the forward-mark computation (via §7.5) and to the operational BAV (via the VolumeSeries module's own logic) — but these are independent consumers, not a chain.

### 7.3 Data shape and grain

**FR-054.** The VolumeSeries module exposes volume data at **atomic-interval grain** in the market's granularity floor (15-min for EPEX DE_LU). The consumption interface returns, for a given (series_key, layer, interval_range):

- `interval_start` — half-open interval start in market-local wall-clock (aligned to MarketCalendar, FR-024)
- `volume_mw` — average MW for the interval (the canonical unit; this is what the position ledger's MW_CAPACITY semantics expect)
- `volume_mwh` — energy for the interval (= `volume_mw` × interval duration in hours; provided by the module, not recomputed by consumers — avoids DST arithmetic errors on the 100/92-interval days)
- `version_id` — the series-version identifier for this data point (used in input-version-set stamping, §7.6)
- `quality_state` — for MeteredActualVolumeSeries: PROVISIONAL | VALIDATED | ESTIMATED; for VolumeSeries(FORECAST): CURRENT | SUPERSEDED; for VolumeSeries(PROFILE): EFFECTIVE | AMENDED

**FR-054a.** Intervals are aligned to the market's atomic grid as defined by MarketCalendar (FR-024/025). The VolumeSeries module must never return data at a grain finer or coarser than the market's floor for that delivery point — grain conversion is the consumer's responsibility via the slot cache (§10, FR-085), not the series module's.

### 7.4 Version model and supersession

**FR-055.** Each volume series is independently versioned using a **supersession model**: a new version for a (series_key, interval_range) supersedes the prior version for that range. Supersession is range-scoped — a validated meter batch for Oct 1–7 supersedes only provisional data for those intervals, not the entire month.

**FR-055a.** Version identity: each supersession produces a monotonically increasing `version_id` scoped to `series_key`. The version_id is opaque to the position/valuation model — it is recorded in the valuation cell's input-version-set for reproducibility but never interpreted or compared except for equality (same version = same data).

**FR-055b.** Supersession semantics by volume source:

| Source | What triggers supersession | Typical frequency | Range granularity |
|---|---|---|---|
| VolumeSeries (PROFILE) | Trade amendment (new delivery profile) | Rare (amendment lifecycle) | Full delivery period of the trade |
| VolumeSeries (FORECAST) | Weather model refresh, re-forecast, asset re-rating | Daily to weekly | Varies: full remaining period or sub-range |
| MeteredActualVolumeSeries | Provisional→validated→estimated meter data; TSO corrections | D+1 provisional, D+5…D+30 validated; occasional late corrections | Day or sub-day batch |

### 7.5 Event contract (supersession → revaluation trigger)

**FR-052.** The valuation layer must react to volume-series supersession events exactly as it reacts to market-data restatements (FR-062): targeted recomputation of affected cells with version stamping (§12).

**FR-052a.** The VolumeSeries module emits a `VolumeSuperseded` event per supersession, carrying:

| Field | Content |
|---|---|
| `series_key` | e.g., `FCST-WP-NORDSEE` or `MTR-WP-NORDSEE` |
| `layer` | VOLUME or METERED_ACTUAL — distinguishes which volume layer was superseded |
| `series_type` | FORECAST / PROFILE (for VolumeSeries layer=VOLUME) or omitted (for layer=METERED_ACTUAL) |
| `affected_range` | Half-open interval range `[start, end)` — the intervals whose volume changed |
| `old_version_id` | Version being superseded (nullable for first publication) |
| `new_version_id` | New version identifier |
| `quality_state` | New quality state (for METERED_ACTUAL: PROVISIONAL→VALIDATED→ESTIMATED transitions) |
| `tenant_id` | Tenant discriminator (always present on all domain events) |
| `event_time` | Processing timestamp (becomes `known_from` on any resulting valuation-cell version) |

**FR-052b.** On receiving `VolumeSuperseded`, the valuation layer:

1. Locates affected valuation cells via the dependency index (FR-102): all cells whose position references `series_key` AND whose interval falls within `affected_range` AND whose dependency edge is active for the volume leaf.
2. For **METERED_ACTUAL** supersession on settlement cells (S5a): re-resolves the price expression with the new metered volume, producing a new cell version (knowledge-time supersession per FR-072). If `quality_state` transitions to VALIDATED and all other inputs are final, the cell status advances to FINAL.
3. For **FORECAST** supersession on forward marks (S5b): re-resolves with the new forecast volume and overwrites the current mark (ephemeral, FR-075). No bitemporal versioning.
4. For **PROFILE** supersession (trade amendment): the ledger itself is versioned first (FR-037); the downstream fan-out and valuation follow from the ledger event, not from the volume event alone. The `VolumeSuperseded` event for PROFILE changes is informational to the volume-side consumers; the position/valuation cascade is driven by the trade-amendment event.

**FR-052c.** The VolumeSeries module also emits a `VolumePublished` event on first publication of a series version (i.e., when `old_version_id` is null). The valuation layer treats `VolumePublished` as the initial materialization trigger — creating forward marks (S5b) or populating the trade interval cache (S6b) for the newly available intervals. Subsequent updates to the same series use `VolumeSuperseded`. Both events carry `tenant_id` for multitenancy routing.

### 7.6 Volume version in the input-version-set

**FR-056.** Every valuation cell's input-version-set (FR-071) includes the volume `version_id` used in resolution, keyed by `series_key`. This is what makes settlement cells reproducible: "show the exact inputs" (FR-116) includes the specific meter version and forecast version, not just the price-side inputs.

**FR-056a.** For flat-block deals (no volume-series reference — fixed `quantity` on the ledger), the volume input is implicit in the position version itself; no separate volume version_id is recorded. The position's own `version` in the input-version-set is sufficient.

### 7.7 Volume in the dependency index

**FR-057.** Volume-series references create dependency-index edges (FR-102) just like market-data references:

- MeteredActualVolumeSeries → settlement cell edges (active while the delivery month is in the hot store)
- VolumeSeries(FORECAST) → forward mark edges (active while the interval is undelivered; pruned on settlement handover per FR-104)

**FR-057a.** Volume edges carry `active_leaves` membership. For the reference deal (collar PPA), the METER leaf is in `active_leaves` for all non-gated intervals (DA ≥ 0) — a meter supersession recomputes those cells. For gated intervals (DA < 0, amount = 0), the METER leaf is inactive — meter supersession does not recompute them, since the volume is irrelevant when the gate produces zero. Note: the DA leaf remains **active** on gated intervals (it is the gate-determining input; a DA restatement above zero would change the result). This is a direct application of the blast-radius optimization (FR-103) to volume inputs: only leaves that cannot affect the output are marked inactive.

### 7.8 Worked example: meter supersession (reference deal)

**Scenario:** VS-3312 METERED_ACTUAL for 2026-08-15 arrives D+1 (Aug 16) as provisional, then is validated D+7 (Aug 22).

| Step | Event | Valuation effect |
|---|---|---|
| Aug 15 (delivery) | Intervals delivered; no meter yet | No settlement cells created (FR-051a: settlement is data-driven) |
| Aug 16 (D+1) | `VolumeSuperseded`: VS-3312/METERED_ACTUAL, range=[Aug-15 00:00, Aug-16 00:00), version=v1, quality=PROVISIONAL | 96 settlement cells created for Aug 15's intervals (each: expression resolved with DA fixing + v1 meter). Cell status = PROVISIONAL. Input-version-set includes `{…, VS-3312:METERED_ACTUAL:v1}`. |
| Aug 22 (D+7) | `VolumeSuperseded`: VS-3312/METERED_ACTUAL, range=[Aug-15 00:00, Aug-16 00:00), version=v2, quality=VALIDATED. QH 18:45 volume changes 11.40→11.52 MWh; QH 03:15 unchanged (but gated anyway). | Dependency index lookup → 96 candidate cells. Gated cells (e.g., 03:15, METER inactive) skipped. Non-gated cells with changed volume (e.g., 18:45): re-resolved → 68.20 × 11.52 = **785.66 EUR** (was 777.48), new cell version, status → FINAL. Non-gated cells with unchanged volume: not rewritten (FR-074). |

This example demonstrates the full chain: volume supersession event → dependency index → active_leaves filtering → targeted recomputation → version stamping → status progression.

---

## 8. S4 — Market Data Store (as consumed by valuation)

### 8.1 Definition

**FR-060.** The Market Data Store holds **shared reference facts**: prices, indices, fixings, FX, and (via the volume interface) meter series — keyed by (series, interval/reference period, as-of/version). A fact is stored **once** and referenced by every consumer; it is never copied onto position or valuation rows (only its *version identifier* is copied, for reproducibility).

### 8.2 Series classes and their clocks

| Class | Example | Interval grain | Publication clock | Restatement behavior |
|---|---|---|---|---|
| Auction settlement | EPEX DE_LU 15-min DA | 15-min | D-1 after auction | Rare corrections; authoritative |
| Forward curve | DE_LU power curve | Curve pillars → interval expansion | Intraday, continuous | Every tick is a new as-of; not "restated" |
| Macro index | HICP (Destatis) | Monthly reference | Monthly, lagged | **Restatements are normal** and may arrive months later |
| FX fixing | ECB EURUSD | Daily | Daily 16:00 CET | Rare corrections |
| Meter (via S3 — listed here for completeness; consumption contract is §7, not S4) | VS-3312 metered actual | 15-min | D+1…D+n, provisional→validated | Supersession is the normal lifecycle |

**FR-061.** Each series is versioned on its own clock, independent of trade and of every other series. A series version has valid time (the reference period it describes) and knowledge time (when published/restated); the HICP restatement in §12.2 is a pure knowledge-time move (ref-month unchanged, new value known later).

**FR-062.** Publication and restatement emit events (`SettlementPublished`, `CurveTick`, `IndexRestated`, `FxPublished`, `VolumeSuperseded`) carrying series key, affected reference range, and new version id. These events are the sole triggers of incremental revaluation (§12). Note: `VolumeSuperseded` with `layer=METERED_ACTUAL` replaces the previously named `MeterSuperseded`; the unified event covers both forecast and metered supersession (see §7.5, FR-052a).

**FR-063.** Forward curves are stored at curve pillars with a defined expansion rule to atomic intervals (shaping profiles); the valuation layer consumes expanded per-interval values but records the **curve version**, not per-interval copies.

---

## 9. S5 — PositionValuation

The valuation layer is where position meets price and fans to interval grain. It is a **derived measure layer** (FR-002) with three sub-populations under different persistence disciplines (FR-012). Conflating them is the primary storage/performance failure mode this spec exists to prevent.

### 9.1 Cell identity

**FR-070.** A valuation cell is identified by (position_id, atomic interval, valuation_type). Valuation types: `SETTLEMENT` (realized), `FORWARD` (unrealized mark), plus the bucketed `EOD_STRUCK` records of §9.4. Cell status: `FORWARD` → `PROVISIONAL` → `FINAL` (settlement side), with the explicit handover of §9.5.

### 9.2 S5a — Settlement cells (durable, bitemporal)

**FR-071.** For each delivered atomic interval of a position, resolution of the price expression against published fixings and metered volume produces a settlement cell: value (price and/or amount), status (PROVISIONAL until all inputs final; then FINAL), `active_leaves`, and the **complete input version set** — every market-data/meter series version and the expression version used.

**FR-071a.** Settlement cell creation requires **all mandatory inputs to be available** for the interval: the fixing price(s) from S4 (e.g., DA settlement for the interval) AND the metered volume from S3 (for shaped deals). A cell is never created from partial inputs — if meter arrives before the fixing (unusual) or fixing publishes before the meter (normal for D-1 auction results arriving before D+1 meter), the cell waits for the later-arriving input. The trigger for cell creation is therefore the **last mandatory input to publish** for that interval. This rule prevents partial-value cells from entering the durable bitemporal store.

**FR-072.** Settlement cells are bitemporal: a restated input or superseded meter produces a new cell version (knowledge-time supersession); the prior version remains for as-of reconstruction. "Reproduce the settlement for interval X as known on date K" is a two-axis filter returning both the value and the exact input versions behind it.

**FR-073.** Population is **time-progressive**: dense over the delivered past, empty over the future. Storage for a deal grows as it delivers; nothing is pre-created. (Reference deal at 2026-11-15: 10,180 cells populated — Aug 2,976 + Sep 2,880 + Oct 2,980 incl. the 100-QH fall-back day + Nov 1–14 1,344; the remaining 24,860 future QH have no rows.)

**FR-074.** Version-binding on unaffected cells after an input restatement follows the **optimized policy**: cells whose value is provably unaffected (input not in `active_leaves`, §12.2) are **not** rewritten; the authoritative input version for any as-of is resolved through the series' own bitemporal history. Only value-changing cells gain new versions. (Decision recorded here; the strict re-stamping alternative is rejected for write amplification.)

### 9.3 S5b — Forward/MtM marks (ephemeral)

**FR-075.** For undelivered intervals, the forward mark (curve × forecast volume through the expression) is maintained as **current state only**: overwritten on revaluation, held in the hot materialization (slot cache / hot store), never bitemporally versioned, never part of durable history. Intraday tick-by-tick mark history has no audit standing and must not be persisted.

**FR-075a.** Initial population of forward marks for a newly booked position: on the **event path**, the first curve tick (`CurveTick` event, FR-062) for the position's market after booking triggers mark computation for all undelivered intervals within the hot window. On the **batch path**, the next scheduled reconciliation cycle (FR-105 step 2) guarantees population regardless of event timing. A newly booked position is therefore marked within seconds (event path active) or at latest by the next batch run (batch-only phase). The position is visible in the slot cache immediately on booking (with volume from FORECAST or fixed quantity), but carries `UNVALUED` marks until curve data is applied.

**FR-076.** Consequently, "what was the intraday mark at 14:32" is explicitly **not** an answerable query, by design. The answerable official questions are: current mark (S5b), and mark as struck at any close of business (S5c).

### 9.4 S5c — EOD struck marks (durable, frozen projection)

**FR-077.** At each close of business, the batch strike (§12.3) computes and persists the **official MtM** per position × **delivery-month bucket** (not per interval) per business day, stamped with the exact curve/FX/index versions used. Struck rows are immutable; a mis-strike is corrected by a superseding strike record, never by editing.

**FR-078.** Bucketing rationale (binding): the audit obligation is "reproduce the official mark and its inputs," which the version stamps satisfy; per-interval durable marks would multiply storage by ×~2,900 (35,040 intervals vs 12 buckets) with no additional audit value, since per-interval detail is re-derivable from ledger + stamped curve version. Reference deal: 12 buckets × ~285 business days ≈ 3,420 rows lifetime.

**FR-079.** The struck mark is the single sanctioned stored aggregate/snapshot in the model (exception noted in FR-007): uni-temporal (keyed by strike as-of), derived, reproducible from S1+S2+S4, and never a source of truth.

### 9.5 FORWARD → SETTLEMENT handover

**FR-07A.** When an interval's settlement inputs publish, the transition is a **single atomic supersession**: the FORWARD state for that (position, interval) is retired and the SETTLEMENT cell created in one transactional step, driven by the settlement event. The interval must never be simultaneously counted as marked (unrealized) and settled (realized); P&L consumers must be able to rely on exactly-one-of at any as-of. This handover is called out as the highest-risk correctness point for the technical design and requires explicit test coverage in the tech spec.

**FR-07B.** Realized/unrealized P&L classification follows cell type: FORWARD ⇒ unrealized; SETTLEMENT ⇒ realized; the day's transition set is reportable (which intervals realized today, at what delta to their last mark).

### 9.6 Worked resolution (reference deal, one interval)

Interval 2026-08-15 QH 18:45 CEST. Inputs: DA15 = 68.20 (DA:v1), HICP 2025-11 = 128.4 (CPI:v1), FX ≡ 1, metered 11.40 MWh (MET:v1→v2 on validation). Escalation f = 128.4/105.2 = 1.22053 ⇒ floor 51.26 / cap 115.95. Neg-gate passes (68.20 ≥ 0); clamp(68.20, 51.26, 115.95) = 68.20 ⇒ collar inside ⇒ CPI inactive. Amount = 68.20 × 1 × 11.40 = **777.48 EUR**. Cell: value 777.48, status PROVISIONAL→FINAL on MET:v2, `active_leaves = {DA15, METER}`, inputs {DA:v1, CPI:v1, FX:v1, MET:v2, expr:v1}. Contrast interval 03:15 (DA = −14.90): neg-gate fires ⇒ 0.00, `active_leaves = {DA15}` (DA is active because it is the gate-determining input — if DA were restated above zero the result would change; METER and CPI are inactive because they cannot affect the output while the gate fires).

---

## 10. S6 — Slot Cache

### 10.1 Definition

**FR-080.** The Slot Cache is the netted, atomic-interval materialization serving the position grid and live board. Grain: (delivery point, portfolio, position type) × atomic interval at the market's granularity floor. Content per cell: `net_mw`, `net_mwh` (both precomputed — FR-081), `is_peak` + `calendar_version` (from §3.4), and a `version_hash` over contributing position versions for staleness detection and idempotency.

**FR-080a.** Volume source for slot-cache population per interval delivery status and deal type:

| Interval status | Flat-block deal | Shaped/pay-as-produced deal |
|---|---|---|
| **Delivered** (past) | Ledger fixed `quantity` (MW replicated per FR-035) | METERED_ACTUAL from S3 (realized shape) |
| **Undelivered** (future, within hot window) | Ledger fixed `quantity` | FORECAST from S3 (expected shape) |

For pay-as-produced deals (e.g., wind PPAs) where the volume comes from a shared asset FORECAST series, the FORECAST is the sole source of undelivered slot-cache volume (each trade's share = forecast × multiplier). If no forecast is available for an interval, the cell is populated with zero and flagged `VOLUME_MISSING` (FR-051a applies to the cache as well as to valuation). For fixed-profile trades (DA fills, shaped bilateral), the VolumeSeries(PROFILE) provides the per-trade volume at multiplier=1.0 — this is the designed resolution for trades with a dedicated delivery profile.

**FR-081.** Dual units are materialized **only here and in rollups** (never in the ledger, which stores one canonical form — FR-035): the cache is where interval duration is fixed and known, so both `net_mw` and `net_mwh` are precomputable without a runtime consistency invariant on the source of truth.

**FR-082.** Netting policy is a property of the projection, not the source: the default cache nets by (delivery point, portfolio, type); alternative netting boundaries (counterparty for credit, gross-by-point for nominations) are additional projections over the same ledger, not variations of the ledger.

### 10.2 Scope discipline

**FR-083.** The cache is dense **only over the hot window** (default: today → T+60 days, plus current delivery month; configurable per tenant/market) and **only for combos with non-zero contribution**. Empty combos and far-dated periods are not materialized; far-dated grid requests are served from rollups (S7). The hottest slice (current/next day for the live board) is additionally fronted by an in-memory tier with the same version-hash contract.

**FR-084.** The cache carries no history and no temporality: it is always "current knowledge, current validity." Historical grids are reconstructed by replaying the ledger's bitemporal filter and re-aggregating on demand (accepted as a slower, rare path — §13.4).

### 10.3 Fan-out / roll-up mathematics

**FR-085.** All granularity conversion resolves through the atomic floor and obeys FR-035:

- Coarser-stored → finer-view (fan-out): MW replicates unchanged per sub-interval; MWh distributes proportionally to sub-interval duration.
- Finer-stored → coarser-view (roll-up): MW time-weighted-averages (weights = interval minutes; mandatory across DST-affected spans and mixed grains); MWh sums.
- Heterogeneous native granularities in one view first land on the atomic grid, then net — no pairwise special-casing.

### 10.4 S6b — Trade Interval Cache (optional per-trade volume breakdown)

**FR-086.** The Trade Interval Cache is an **optional** rebuildable materialization that stores pre-multiplied resolved volume per trade-leg per atomic interval. Where S6 (Slot Cache) nets across trades to show aggregate portfolio position, S6b retains the per-trade breakdown for portfolio-detail dashboards.

**FR-086a.** Grain: (trade_leg_id × atomic interval). Content per cell: `resolved_mw` (= `volume_interval.volume × volume_reference.multiplier`), `resolved_mwh` (= `volume_interval.energy × volume_reference.multiplier`), `multiplier` (stored for auditability), `series_key` (which volume series was used), `version_hash` (staleness detection).

**FR-086b.** S6b is populated by the same event-driven path as S6: `VolumeSuperseded` events trigger rebuild of affected trade intervals; `VolumeReference` changes (multiplier update, new allocation) trigger rebuild of that trade-leg's intervals; trade amendments trigger rebuild of affected intervals. All rebuilds are idempotent (re-derive from source per FR-106).

**FR-086c.** Without S6b, per-trade interval-level volume requires a runtime join: `position_ledger → volume_reference → volume_interval`, applying the multiplier at query time. This is acceptable for small result sets (a few trades × one day = ~500 rows) but degrades for portfolio-detail dashboards showing many trades across extended periods (200 trades × 2,976 intervals = ~595K rows joined and multiplied on the fly). S6b reduces this to a single-table indexed scan.

**FR-086d.** S6b is explicitly **not source of truth**: entirely rebuildable from `volume_reference` + `volume_interval`. Systems that do not require per-trade dashboards may omit it. Sizing: `(P + T×Bf) × hot_months × D × I` = 5,000 × 2 × 30.4 × 96 ≈ **29M rows** in the hot window (2 months).

**FR-086e.** Commodity neutrality: S6b's resolution pattern (`volume × multiplier → resolved quantity`) is unit-agnostic. For non-power commodities, the columns generalize: `resolved_mw/mwh` → `resolved_qty/energy` (or `resolved_rate/quantity` depending on the commodity's rate/amount duality — FR-035 semantics apply). The index key (`trade_leg_id, interval_start`) and the rebuild triggers are identical across commodities.

---

## 11. S7 — Rollup Aggregates

**FR-090.** Rollups materialize coarse-grain measures for reporting and far-dated views: hourly / daily / monthly (and delivery-year) nets and values, each split by peak/off-peak using the interval dimension's `is_peak` (trivial because peak is materialized per interval, FR-026). Materialized values per rollup cell:

- **net_mw** — time-weighted average MW (weights = interval minutes; mandatory for DST-correct aggregation per FR-085)
- **net_mwh** — sum of MWh across constituent intervals
- **settled_value** — sum of settlement cell amounts (S5a) in settlement currency, for delivered intervals within the rollup period; null where no settlement exists yet
- **forward_mark_value** — sum of current forward mark amounts (S5b) in settlement currency, for undelivered intervals; overwritten on each batch refresh

All monetary rollups are **per settlement currency** — positions with different currencies produce separate rollup rows. Cross-currency aggregation (e.g., portfolio-level EUR+GBP total) is a display-time FX conversion at the report's chosen rate, never a pre-aggregated rollup value.

**FR-091.** Rollups are rebuildable, refreshed on the batch cycle (§12.3), versioned against the calendar version, and are the serving layer for any grid request beyond the hot window and for dashboard/regulatory extracts. They inherit the netting-is-projection rule (FR-082).

---

## 12. Processing Model

### 12.1 Two cadences, one logic

**FR-100.** All derivation (resolution, netting, rollup) is defined once and invoked at two cadences:

- **Event path** — incremental, targeted, low-latency: keeps hot cells fresh (seconds) after fills, amendments, fixings, meter supersessions, curve movement. Scope is always "affected cells only," located via the dependency index. The event path is a freshness optimization and is **never authoritative**.
- **Batch path** — scheduled, whole-book, authoritative: EOD strike (S5c), rolling cascade, rollup refresh, full reconciliation of derived layers against S1/S2/S4, retention/archival. Batch reads in bulk by query.

**FR-101.** Authority ordering is fixed: batch is the source of correctness and self-heals the event path (a dropped/late event leaves a cell stale at worst until the next reconcile — never wrong in the durable record). If the platform ships in stages, batch-only is a valid first stage; the event path is additive.

### 12.2 Dependency index & blast radius

**FR-102.** Every resolution upserts reverse-dependency edges: (input series key → valuation cell), each edge carrying the cell's `active_leaves` membership for that input. An input publication/restatement locates its affected cells by index lookup over the affected reference range — never by scanning the valuation store.

**FR-103.** Restatement recomputation is filtered to **active** edges. Worked case (reference deal): HICP 2025-11 restated 128.4 → 128.9 on 2027-03-11 (knowledge-time move). Candidate scope = all 2026-delivery cells referencing CPI; value-change scope = only collar-binding cells (floor/cap selected). Inside-collar cells (CPI inactive) and negative-gated cells are provably unaffected without re-evaluation and, per FR-074, are not rewritten. Example deltas: a floor-bound night QH re-versions from floor 51.26 → 51.46 basis; QH 18:45 (68.20 inside collar) is untouched.

**FR-104.** Index lifecycle: edges for a cell are pruned by class once irrelevant — forward-curve edges drop when the interval settles final; settlement-input edges persist until the delivery month leaves the hot store per retention. The hot index is thereby sized to open exposure, not lifetime history.

### 12.3 Batch cycle (authoritative)

**FR-105.** The scheduled cycle comprises, in dependency order: (1) rolling cascade expansion (FR-03A); (2) full re-derivation/reconciliation of settlement cells and slot cache against S1/S2/S4 within the hot window, healing any event-path drift; (3) EOD strike per FR-077 with version stamping; (4) rollup refresh; (5) retention actions — partition-window archival of aged delivery months to the warm/cold tiers per the platform retention model. Each step is restartable and idempotent.

### 12.4 Idempotency & replay safety

**FR-106.** All recomputation is **re-derive, not delta-apply**: a consumer recomputes affected cells from sources and upserts keyed by (value, input version set). Redelivered or replayed events re-derive identical results and no-op — exactly-once *effect* over at-least-once *delivery*, with no distributed transactions. This depends on resolution purity (FR-047) and the version-set stamp, both mandatory.

**FR-107.** Ordering tolerance: because recomputation is from-source, out-of-order events (late meter after settlement, curve tick racing a fixing) converge to the same terminal state; the FORWARD→SETTLEMENT handover (FR-07A) is the one transition requiring explicit transactional sequencing.

---

## 13. Read Paths

### 13.1 Position grid (horizontal interval view)

**FR-110.** Request: (tenant, portfolio set, delivery point set, date range, view granularity, position type/layer set). Behavior:

1. Generate the display grid descriptor from the MarketCalendar (FR-024/025): ordered atomic intervals for the range in market-local wall-clock, with DST-correct counts (92/96/100 per EU day), duplicated-hour disambiguation, `is_peak` mask, and DST markers for rendering.
2. Serve from the slot cache for in-hot-window ranges; from rollups for far-dated ranges; the routing is transparent to the caller.
3. Project cells onto the descriptor; density (empty cells) is generated here, not read (FR-011).
4. Convert to view granularity per FR-085.
5. Return a dense array aligned to the descriptor plus the descriptor itself; the client renders columns from the descriptor and must not compute interval structure locally.

**FR-111.** A trade of any native granularity is visible in any view granularity: yearly/monthly/daily/hourly/30-min obligations appear across the atomic slots they cover (fan-out per FR-085); finer-than-view data rolls up. This satisfies the original multi-granularity display requirement, including the 96/100/92-interval day cases.

**FR-112.** Live updates: cells changed since the client's last known `version_hash` are pushable as deltas; the hash contract makes redisplay incremental and duplicate-safe.

### 13.2 Drill-down

**FR-113.** Every netted grid cell must be explodable to its constituents: (delivery point, portfolio, type, interval) → contributing position blocks (ledger rows, current versions) → source trades/legs, with each constituent's fanned contribution to that interval. Netting is therefore never information-destroying for the user; the ledger grain guarantees this.

### 13.3 Valuation views

**FR-114.** Per position and per aggregation node: current forward mark (S5b), realized settlement to date (S5a, summable by period/peak split), last struck official mark with its as-of and input versions (S5c), and today's realization transitions (FR-07B).

### 13.4 As-of / audit path

**FR-115.** Any historical state — positions or settlement values — is reconstructible for (business date B, knowledge date K) by the bitemporal filter (FR-007), including netted views recomputed on demand from the as-of ledger slice. This path is correctness-critical but latency-relaxed (rare, audit/dispute-driven); it must never be optimized at the expense of the current-state hot path, and vice versa the hot path must never scan history (current-state filter is `known_to` open).

**FR-116.** For every struck mark and settlement cell, the system must answer "show the exact inputs" from stamps alone: expression version, every series version, calendar version — sufficient to re-execute resolution and reproduce the number bit-for-bit.

---

## 14. Multitenancy, Partitioning & Retention (functional requirements)

**FR-120.** Every structure is tenant-scoped; no query may cross tenants. Tenant isolation composes with the platform's existing isolation stack (per-tenant pooling, bulkheads, WFQ, tiered topics); nothing in this model may require cross-tenant scans.

**FR-121.** Time-partitionability is a functional requirement: S1 and S5a are organized by **delivery month** (which is why the ledger decomposes to month blocks, FR-030/031); S5c by **strike (as-of) month** — its access pattern is observation-dated, not delivery-dated, and the two axes must not be conflated. Any delivery-window or strike-window query must be prunable to the relevant months for one tenant.

**FR-122.** Tenant dimension default: shared partition sets with tenant as the leading isolation key (pruning by index/policy), avoiding partition-count explosion (36 retained months, not 200×36). Large tenants are promotable to dedicated clusters with unchanged schema. Sub-partitioning by tenant is not the default and requires a demonstrated per-tenant month-slice scan problem.

**FR-123.** Retention follows the platform's quality-driven regulatory model: hot store holds open exposure plus the regulatory working window (12–14 months post-delivery for settlement detail); aged delivery months move to warm (compliance archive) then cold tiers, remaining reproducible for the full REMIT/MiFID retention horizon. Ephemeral forward marks (S5b) have no retention at all; struck marks (S5c) retain for the full horizon (they are tiny).

**FR-124.** Rebuildability across tiers: any archived derived data must be re-derivable from archived S1/S2/S4 alone; archives of derived layers are a convenience, not a dependency.

---

## 15. Sizing Model (with derivations)

All figures derive from the reference deal and scale linearly; the tech spec must re-validate constants but not the structure of the derivation.

### 15.1 Ledger (S1)

Rows = delivery months × versions. Reference deal: 12 blocks × ~2 versions = **24 rows** per full-year 15-min PPA. Sizing uses the **large-tenant scenario** (~300 live deals, amendment factor ≤5) as the worst-case bound: ≤ 300 × 12 × 5 = **18,000 rows** — negligible. The fleet-average tenant is smaller (~25 trade-legs: 8 assets × 2.5 trades/asset + 5 fixed-profile trades; see README §10.4 sizing assumptions); fleet-wide at 200 average tenants: ~120K rows. Derivation shows bitemporality multiplies by the amendment factor (2–5×), never by intervals: this is the direct payoff of FR-004/030.

### 15.2 Settlement cells (S5a) — the dominant durable term

Rows ≈ delivered intervals × supersession factor. Reference deal lifetime: 35,040 QH × ~1.3 (provisional→final + occasional restatement) ≈ **45,500 rows**. Large tenant (300 deals): ≈ **13.7M rows** per delivered year; the hot store holds ≤ the 12–14-month regulatory window of this. Fleet at average tenant size (200 tenants × 25 trade-legs): ≈ **2.7B rows total across all tiers** — a storage figure, not a query figure (see 15.5).

**5-min granularity sensitivity:** At 5-min settlement (Nordic markets today; Central EU proposed ~2028), the same reference deal produces 105,120 intervals × 1.3 ≈ **136,700 rows** (×3). Tenant at 300 deals: ≈ **41M rows/delivered year**; fleet: ≈ **8.2B total**. The partition/index strategy (delivery-month, tenant-leading key, current-state filter) remains viable — worst monthly scan grows from ~1.1M to ~3.3M candidates, well within Aurora's indexed-scan capability. The hot-window length (FR-083) becomes the primary control knob at finer floors and must be validated per market before 5-min activation.

### 15.3 Struck marks (S5c)

Rows = buckets × business days: 12 × ~285 ≈ **3,420** per deal lifetime; tenant ≈ 1.0M over a year of striking a 300-deal book — small. Derivation of the ×~2,900 avoidance: per-interval durable marks would be 35,040/12 ≈ 2,920× larger for zero audit gain (FR-078).

### 15.4 The avoided explosion (S5b)

Had forward marks been durable+bitemporal: 24,860 open QH (reference deal at mid-life) × O(100s) of curve revaluations over the deal's marking life ⇒ O(10⁹) rows *per deal*. FR-075 reduces this to **zero durable rows**. This single classification decision is the difference between a viable and a non-viable valuation store; it is why FR-012 is marked non-negotiable.

### 15.5 Query-time bound

Every hot query is (one tenant) × (pruned delivery months) × (current-state filter). Worst realistic scan: one tenant × one delivery month × 300 deals ≈ 300 × 35,040/12 × 1.3 ≈ **1.1M candidate settlement rows** before index selection — typically far fewer after range/current-state filtering. Aggregate fleet volume never appears in any query's working set; multitenant scale is a storage/tiering concern, not a latency concern, provided FR-120–122 hold.

### 15.6 Slot cache (S6)

Hot window 60d × 96 QH = 5,760 cells per live (point, portfolio, type) combo; a tenant with low-hundreds of combos ⇒ **low millions of cells**, rebuildable. Granularity-floor sensitivity: at a 5-min floor the same window is 17,280 cells/combo (×3); at 1-min, 86,400 (×15) — the hot-window length is the control knob and must be configurable per market (FR-083).

### 15.7 Trade interval cache (S6b, optional)

Rows = (total trade-legs) × hot-window intervals. Reference fleet: (P + T×Bf) × hot_months × D × I = 5,000 × 2 × 30.4 × 96 ≈ **29M rows** in the 2-month hot window. Comparable to the slot cache in magnitude but keyed per trade-leg rather than per (point, portfolio, type). At 5-min granularity: ×3 → ~87M rows (still within indexed-scan viability). The cache is entirely optional — systems without per-trade dashboards omit it with no functional loss.

### 15.8 Dependency index (S8)

Edges ≈ open (position × interval) cells × ~4 leaves, bounded by FR-104 pruning to open exposure — same order as the open slice of S5, not lifetime history.

---

## 16. Decisions Recorded & Open Items for the Technical Phase

### 16.0 Platform constraints (binding on the technical spec)

The technical spec inherits the platform's technology decisions. These are not revisited here but must be respected: Java 21 / Spring Boot 3.3 / Aurora PostgreSQL 16 (declarative partitioning + pg_partman + pg_cron; NO TimescaleDB — invalid on RDS/Aurora) / Kafka 3.7 KRaft (batch listeners, manual commit, at-least-once with idempotent effect per FR-106) / Redis 7 / `GenerationType.SEQUENCE` allocationSize=50 (never IDENTITY) / Flyway-managed DDL including partition lifecycle / per-tenant Hikari partitioning + PgBouncer transaction pooling. Full platform constants are documented in `CONTEXT-position-valuation-design.md` §11.

### 16.1 Decisions this spec fixes (tech spec must comply)

| # | Decision |
|---|---|
| D-1 | Ledger grain = trade-leg × delivery-month block; signed quantity; no direction enum; no interval fan-out in S1 |
| D-2 | Price is an expression reference; fixed price is the degenerate expression; per-interval prices are derived measures |
| D-3 | Forward marks ephemeral; settlement bitemporal; official MtM = per-month-bucket EOD strike with input-version stamps |
| D-4 | Optimized version-binding on restatement (unaffected cells not rewritten); `active_leaves` captured at first resolution |
| D-5 | Peak is interval-dimension data, calendar-versioned; never on position rows |
| D-6 | Dual units (MW+MWh) materialized only in cache/rollups; single canonical in ledger |
| D-7 | Batch authoritative, events additive freshness; re-derive-not-delta idempotency |
| D-8 | Entity/measure distinction: position-ledger block is entity (lifecycle-bearing); zone×portfolio net is measure (projection/materialization policy). Netting is projection policy; default cache nets by (point, portfolio, type); drill-down mandatory |
| D-9 | Shared monthly partitions with tenant as leading isolation key; S5c on strike-month axis |
| D-10 | All interval structure via MarketCalendar; no timestamp arithmetic anywhere |
| D-11 | Unified volume resolution: every trade via VolumeReference → VolumeSeries × multiplier; fixed-profile = degenerate case (multiplier=1.0, per-trade PROFILE series); no category branching in code |
| D-12 | S6b trade_interval_cache: optional, rebuildable, event-driven per-trade pre-multiplied volume cache; not source of truth; commodity-neutral (resolved_qty/energy columns generalize across power/gas/oil/ags); indexed by (trade_leg_id, interval_start) |

### 16.2 Open items to resolve in (or before) the technical spec

| # | Item | Notes |
|---|---|---|
| O-1 | Hot-window length & per-market defaults | 60d assumed; validate against desk usage, especially if 5-/1-min floors activate (15.6) |
| O-2 | Live board necessity per tenant tier | Determines whether the event path ships in phase 1 or batch-only suffices initially (FR-101). **Latency implication:** batch-only means XBID continuous-market fills show stale positions until next batch run — acceptable for term-trading tenants but not for intraday-focused desks. Resolution must define which tenant tiers get batch-only vs event-driven in phase 1. |
| O-3 | Expression language surface | Which operator set is v1 (collar, gate, escalation, FX, averaging) vs deferred (baskets, options-like payoffs) |
| O-4 | Settlement-amount vs settlement-price cell content | Store price, amount, or both per cell; interacts with cashflow module contract |
| O-5 | Struck-mark bucket for sub-monthly books | **Recommendation:** monthly buckets for term books (`native_granularity` > WEEK); **daily buckets** for spot/intraday books (`native_granularity` ≤ WEEK) — a single day-ahead deal's entire lifetime fits inside one monthly bucket, making per-deal P&L attribution from strikes impossible without re-derivation. Daily buckets for short-tenor adds ~30× rows for those deals only (30 days × 1 bucket vs 1 month × 1 bucket) but enables desk-level daily P&L directly from struck marks. Confirm with P&L/cashflow consumers. |
| O-6 | Curve-pillar → interval expansion ownership | Market-data service vs valuation-side expansion; affects what "curve version" stamps mean (FR-063) |
| O-7 | Dispute/annotation workflow on settlement cells | Whether disputes are a status on cells or a sibling structure |
| O-8 | Cross-zone financial-net flagging in UI contracts | How FR-023 flags surface in grid/rollup payloads |

---

## 17. Glossary

| Term | Meaning |
|---|---|
| `active_leaves` | The set of expression-tree leaves that actually determined a cell's output value; gate-determining inputs (e.g., DA for negative-price gate) are active; downstream leaves rendered irrelevant by the gate are inactive (§6.4, §12.2) |
| Atomic interval / granularity floor | The finest interval a market settles/schedules at (EPEX DE_LU: 15-min; extensible to 5-/1-min) |
| BAV | Best Available Volume — operational effective volume after overlays (VolumeSeries module) |
| Bitemporal | Versioned on independent valid-time and knowledge-time axes (§2.3) |
| Block | One delivery-month slice of a trade-leg obligation; the ledger row grain |
| Cascade | Exchange decomposition of coarse products into finer delivery obligations (Cal→Q→M→…) |
| Cell | One (position, interval, type) valuation record |
| PROFILE / FORECAST / METERED_ACTUAL | The three volume sources consumed by position/valuation (§7.2): PROFILE (VolumeSeries, seriesType=PROFILE) = obligation shape per trade-leg; FORECAST (VolumeSeries, seriesType=FORECAST) = expected generation per asset; METERED_ACTUAL (MeteredActualVolumeSeries) = delivered measurement per asset. All consumed via VolumeReference × multiplier (D-11). |
| Entity / measure | §2.1; lifecycle-bearing object vs coordinate-identified number |
| Fan-out / roll-up | Granularity conversion downward/upward per FR-085 |
| Grid descriptor | MarketCalendar-generated interval list + peak/DST metadata driving display |
| Hot window | The date range over which the slot cache is densely materialized |
| Input version set | The stamped versions of every series + expression + volume used to resolve a cell |
| Struck mark | The immutable official EOD MtM per position × month bucket |
| Trade Interval Cache (S6b) | Optional rebuildable per-trade pre-multiplied volume cache (resolved_qty = volume × multiplier); commodity-neutral; event-driven rebuild on VolumeSuperseded / reference change (§10.4) |
| Supersession | Version replacement by closing knowledge time and appending, never editing |
| VOLUME_MISSING | Data-quality flag on a slot-cache or forward-mark cell indicating no forecast volume was available for the interval |
| VolumeSuperseded | Event emitted by the VolumeSeries module when a layer's data is replaced for a range of intervals (§7.5) |

---

*End of functional specification v1.0 (draft). Next phase — on explicit request only — technical specification: schemas/DDL, partition/index design, event contracts, API shapes, derived from the FR/D items above.*
