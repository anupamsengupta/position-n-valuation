# CONTEXT — Position Generation, PriceExpression & Valuation Design

**Purpose of this file:** Distilled context from the full design conversation (Claude AI, Jul-2026) for use in Claude Code sessions. Read this before touching any position/valuation code. The authoritative functional spec is `docs/specs/functional-spec-position-valuation-v1.0.md` — this file explains *how we got there* and *what was rejected and why*, so decisions are not accidentally re-litigated during implementation.

**Companion documents:**
- `functional-spec-position-valuation-v1.0.md` — the binding functional spec (FR-nnn rules, D-1…D-10 decisions, O-1…O-8 open items). Cite FR/D numbers in implementation prompts.
- `VOLUME_SERIES_SPEC-V3_0.md` — the volume series domain model (unified VolumeSeries + VolumeReference pattern). V3.0 is a breaking rewrite of V2.1.
- UX Spec V1.0 — grid/board UI (consumes the read-path contracts defined here).

---

## 1. Problem statement (as originally posed)

Multitenant SaaS CTRM for EU power (EPEX/EEX/NORDPOOL/XBID), ~200 small/mid tenants. Position generation + display:

- Markets settle at 15-min floor today; design must extend to 5-min (US) and 1-min.
- Horizontal grid view: a day at 15-min = 96 intervals (100 on DST fall-back day, 92 on spring-forward day).
- Any-granularity trade visible in any-granularity view (30-min deal spans 2 cells of a 15-min view; yearly deal visible at atomic grain).
- Persistence must make grid reads fast without runtime computation.

The initially proposed model (single Position class with Source{DB,FILL}, DirectionType{BUY,SELL}, PeakType on the row, scalar price+currency, BiddingZone, granularity + periodStart/End) was reviewed and substantially reworked. The corrections below are the core of this context.

---

## 2. Design corrections made during the conversation (do NOT regress these)

### 2.1 Source enum DB/FILL — abolished
It conflated staging lifecycle with business origin. Split into:
- `stagingState` (STAGING → CONFIRMED) on the capture pipeline (fill-from-exchange until saved as trade).
- `originType` ∈ {EXCHANGE_FILL, BILATERAL_TRADE, INTERNAL_TRANSFER, MANUAL_POSITION_ADJ} on the ledger.
- Execution venue (EPEX_SPOT etc.) is a `Market` reference entity (timezone, peak calendar, holiday calendar, gate-closure, granularity floor, MIC) — never an enum value.

### 2.2 Positions vs volume series — the key split
User correctly identified: NOMINATION, SCHEDULE, FORECAST, ACTUAL, IMBALANCE are **volume series layers**, not positions. Final model:
- **Position** = trade-derived economic exposure (price-bearing, signed, portfolio-attributed, bitemporal). Only contractual trade exposure lives in the position ledger.
- **VolumeSeries** (V3.0 module) owns volume data with supersession. Unified model: every trade resolves volume via `VolumeReference` → `VolumeSeries` × `multiplier`. FORECAST series (per asset, shared) and PROFILE series (per trade, dedicated) stored in one unified table; MeteredActualVolumeSeries stays separate.
- MTM_REVAL is not a source at all — it's a valuation event against an existing position.
- Same trade's MW shape legitimately exists in two projections (risk vs ops); neither derives from the other's materialization.
- **Volume is per asset** for renewable PPAs: one forecast + one meter per physical asset. Multiple trades slice the asset's output via capacity multipliers on `VolumeReference`. Fixed-profile trades (DA, bilateral) are a **degenerate case**: per-trade PROFILE series with `multiplier=1.0`. Same code path for all trades.

### 2.3 PeakType — off the position row
Peak/off-peak is a deterministic function of (market, interval local wall-clock, holiday calendar). Unrelated to write time or gate closure (user initially conflated these — clarified: gate closure is a tradability rule, no effect on peak flags). Resolution: materialize `is_peak` once per (market, atomic interval) into the interval dimension / slot cache, versioned by `calendar_version`. Never stored on position rows; never resolved per-record at display time (that was the user's legitimate perf concern — answered by interval-grain materialization, not row-level storage).

### 2.4 Direction — signed quantity
BUY/SELL conflates trade action with position sign. Ledger stores **signed quantity** (long +, short −); LONG/SHORT is a display projection. Net = plain unit-aware sum.

### 2.5 BiddingZone → DeliveryPoint hierarchy
Polymorphic: BiddingZone (EU) | Hub/Zone/Node (US, future) | MeteringPoint (EIC-W) | VirtualPoint. Store at finest applicable point; **zone is the aggregation/netting boundary**, not the storage grain. Netting within a bidding zone is physical; across zones is financial-only (needs transmission rights) and must be flagged as such.

### 2.6 MW vs MWh — single canonical in ledger, both in cache
User asked to store both to avoid runtime conversion. Resolution: storing both in the source of truth creates a consistency invariant that will be violated on amendment. Ledger stores canonical MW + interval bounds; the **slot cache** (where interval duration is fixed) materializes both `net_mw` and `net_mwh` precomputed. Aggregation law (binding everywhere): MW replicates on fan-out and time-weight-averages on roll-up; MWh distributes on fan-out and sums on roll-up. Never sum MW; never replicate MWh.

### 2.7 Bitemporality — kept on positions, not replaced by BAV
User asked if BAV could serve instead. No: BAV answers "what will physically flow"; the bitemporal position ledger answers "what was our booked risk as known at time T" (the REMIT/EMIR audit question). Two axes per version: valid_from/to (business truth) + known_from/to (system knowledge). Append-only supersession; snapshots reconstructed by query, never stored (single exception: EOD struck mark, a deliberately frozen derived artifact). Backdated corrections move knowledge time only; forward-effective events (unwinds) move valid time. Worked 7-day example exists in the conversation: 4 events → 5 ledger rows → same business date returns 4 different answers as knowledge date advances.

### 2.8 Grain decision — entity vs measure (the deciding argument)
Question was: is "the position" a zone×portfolio net or a trade-leg record? Answer: **trade-leg obligation is the entity** (has identity + lifecycle; amendments target it); **zone×portfolio net is a measure** (identity = coordinates only; no lifecycle of its own). Bitemporal ledgers store entities; measures are computed/materialized. Storing the net as truth fails on: lossy/irreversible (no drill-down/unwind), reg reporting needs transaction grain anyway, bitemporality incoherent (versions triggered by events elsewhere), and netting is a per-consumer policy (risk=portfolio, credit=counterparty, nominations=gross) that must not be frozen into the source grain. Exploding trade→interval rows as the grain is equally wrong: materialization decision, not grain-of-record decision.

### 2.9 Price — from scalar to PriceExpression
Three prices disentangled: capture/trade price (trade-leg attribute, fixed for fixed-price deals), market/settlement price (shared reference fact keyed by point×interval×as-of — never copied onto positions), MtM/P&L (derived measures). Then corrected further for PPAs: **formula-priced deals have no capture price at all** — the price is a per-interval function of multiple curves. Model: position carries `price_expression_ref`; `PriceExpression` is a versioned tree (leaves = market-data refs + constants; nodes = arithmetic, clamp/collar, conditional neg-price gate, escalation, FX, time-averaging). Fixed price = degenerate single-constant expression (no schema migration for indexed deals). Per-interval resolved values land in the valuation layer, stamped with every input version. Clause precedence is contract law (neg-price zeroing OVERRIDES the floor in the reference deal) and must be encoded per expression, not hard-coded.

### 2.10 Valuation volume — the real scaling risk, and the fix
User challenged: bitemporal × multitenant = explosion? Answer: the **ledger** does not explode (bitemporality multiplies by amendment factor 2–5×, never by intervals — ~24 rows for a full-year PPA). The **valuation layer** is where volume lives. Controls (non-negotiable):
- **Forward/MtM marks are EPHEMERAL** — current-state only, overwritten, cache-held, never bitemporally persisted. "Mark at 14:32" is deliberately unanswerable. Avoids O(10⁹) rows/deal.
- **Settlement cells** (realized, delivered intervals): durable + bitemporal, time-progressive (dense past, empty future). ~45.5k rows per full-year 15-min deal lifetime.
- **EOD struck marks**: durable, immutable, per position × **delivery-month bucket** × business day (NOT per interval — ×~2,900 savings), stamped with curve/FX/index versions for reproducibility. ~3.4k rows/deal lifetime.

### 2.11 Event vs batch — false dichotomy resolved
Both, same derivation logic, two cadences. **Event path**: incremental, dependency-index-targeted, keeps hot cells fresh in seconds; never authoritative. **Batch path**: EOD strike, rolling cascade, rollup refresh, full reconciliation (self-heals event drift), archival; **authoritative**. If dropping one, drop events. Batch-only is a valid phase-1 ship. Idempotency = re-derive-from-source (not delta-apply), keyed by (value, input version set) → exactly-once effect over at-least-once delivery, tolerant of replay and reordering. One transactional exception: the FORWARD→SETTLEMENT handover (single atomic supersession; highest-risk correctness point; never double-count an interval as marked and settled).

---

## 3. The structure set (S1–S8) — one-line each

| # | Structure | Grain | Nature |
|---|---|---|---|
| S1 | Position Ledger | trade-leg × delivery-month block | bitemporal entity, sparse, source of truth (~24 rows/yr-deal) |
| S2 | PriceExpression | expression version per leg | bitemporal entity, tree; fixed price = degenerate |
| S3 | VolumeSeries (V3.0 unified) | series × interval | external module; consumed via VolumeReference × multiplier (FORECAST per asset / PROFILE per trade / MeteredActual per asset) |
| S4 | Market Data Store | series × interval × as-of | shared reference facts, per-series clocks; restatement events |
| S5a | Settlement cells | position × atomic interval | durable bitemporal measure; dense over delivered past only; `active_leaves` + full input-version stamps |
| S5b | Forward marks | position × atomic interval | EPHEMERAL current-state; zero durable rows |
| S5c | EOD struck marks | position × month-bucket × business day | durable immutable frozen projection |
| S6 | Slot Cache | (point×portfolio×type) × atomic interval | rebuildable netted materialization; hot window only (default T+60d); net_mw + net_mwh + is_peak + version_hash |
| S7 | Rollups | hour/day/month × peak split | rebuildable; serves far-dated + reporting |
| S8 | Dependency index | input-series → cell edges | with active-flag; restatement blast-radius; pruned to open exposure |

Dependency is strictly one-way: S1/S2 (+S3/S4 inputs) ⇒ S5 ⇒ S6/S7. Everything right of S4 is rebuildable.

### Ledger grain detail (D-1)
Delivery-month blocks — not 1 row per leg (defeats delivery-month partition pruning), not interval rows (5 orders of magnitude for zero attribution gain). A 12-month deal = 12 blocks (POS-5501…POS-5512), each wholly inside one monthly partition. Shape within a block is delegated by reference to the volume series; attributes constant within a block; mid-month term changes handled by valid-time splitting of the block's versions.

### `active_leaves` mechanism (D-4)
Captured at FIRST resolution (cannot be cheaply retrofitted): which leaves actually determined the output (collar-inside ⇒ CPI referenced-but-inactive; neg-gated ⇒ gate only). On input restatement (e.g. HICP 128.4→128.9), dependency lookup filters to active edges → only collar-binding cells recompute; inside-collar and gated cells are provably unaffected WITHOUT re-evaluation and are NOT rewritten (optimized version-binding: authoritative input version for unaffected cells resolves through the series' own bitemporal history at query time).

---

## 4. Reference deal (used in all worked examples)

`T-7788`, tenant `TN_0042`: EPEX DE_LU pay-as-produced wind PPA, offtaker/buy, 2026-08-01→2027-08-01 Europe/Berlin, 15-min settled. Volume ref `VS-3312`; price ref `PXE-9001` = collar(42/95 @2025 base) × HICP escalation (ref 2025-11 for DY2026, 2026-11 for DY2027) + neg-price zeroing (overrides floor) + degenerate FX. 35,040 QH total (363×96 + 100 fall-back + 92 spring-forward).

Worked interval 2026-08-15 QH18:45: DA 68.20, f=128.4/105.2=1.22053 ⇒ collar [51.26,115.95], inside ⇒ price 68.20, ×11.40 MWh = 777.48 EUR; active_leaves={DA,METER}, CPI inactive. Interval 03:15: DA −14.90 ⇒ gate ⇒ 0.00, active_leaves={}.

---

## 5. Multitenancy, partitioning, sizing (settled)

- Partition S1/S5a by **delivery month** (pg_partman, monthly, premake ahead, retention→detach+archive). S5c by **strike/as-of month** (different axis — observation-dated access pattern; do not conflate).
- Tenant = leading isolation key on shared partitions (36 retained months, not 200×36 children). Large tenants promotable to dedicated Aurora clusters, schema unchanged. Sub-partition by tenant only if a single tenant's month-slice is demonstrably too big (it isn't at current sizing).
- Sizing per full-year 15-min deal: S1 ~24, S5a ~45.5k (35,040×1.3), S5c ~3.4k ⇒ ~49k durable rows/deal. Tenant@300 deals ≈ 13.7M settlement rows/delivered year; fleet@200 ≈ 2.7B **total storage across tiers** — never a query working set. Worst hot scan: 1 tenant × 1 delivery month ≈ ~1.1M candidates pre-index. Hot Aurora holds open exposure + 12–14-month reg window; older → S3 → Glacier; derived layers re-derivable from archived S1/S2/S4.
- Query hot path: tenant + month-partition prune + current-state filter (`known_to` open, partial-indexed) + range overlap. As-of/audit path is latency-relaxed by design; never let it degrade the hot path or vice versa.

---

## 6. DST & calendar rules (binding)

- MarketCalendar service is the SOLE authority for interval structure. NO timestamp arithmetic (`start + n×15min`) anywhere — grid generation, fan-out, cascade, all via calendar.
- EU day = 96 QH normal, 100 fall-back (2026-10-25), 92 spring-forward (2027-03-28); duplicated fall-back hour explicitly disambiguated.
- Market-local wall-clock semantics for delivery ranges; grid descriptor (intervals + peak mask + DST markers) is server-generated and shipped to the client, which must not compute interval structure locally.

---

## 7. Read paths (contracts for UI)

- **Grid**: calendar descriptor → slot cache (hot window) or rollups (far-dated, transparent routing) → project onto grid (density generated, not stored) → granularity conversion per MW/MWh laws → dense array + descriptor. Column-axis virtualization on client. Delta pushes keyed by version_hash.
- **Drill-down**: every netted cell explodable → contributing ledger blocks → source trades/legs with per-interval fanned contribution. Ledger grain guarantees this is always possible.
- **Valuation views**: current forward mark + realized-to-date + last struck mark (with as-of + input versions) + today's realization transitions.
- **As-of/audit**: any (business date, knowledge date) state reconstructible; every struck/settlement value reproducible bit-for-bit from stamps (expr version + all series versions + calendar version).

---

## 8. Multi-commodity note (deferred, shapes the core)

Core (S1 bitemporal ledger, delivery ranges, DeliveryPoint hierarchy, PriceExpression, derived-measure layering) is commodity-neutral. Power plug-in = dense grid + MW/MWh duality + peak + cascade. Gas would add offset gas-day calendar + basis; oil/ags would add parcel/lot + grade + formula differentials. DO NOT build these speculatively — but the two cheap-now decisions that enable them are already taken: `price_expression_ref` instead of scalar price, and polymorphic DeliveryPoint.

---

## 9. Volume Series unified model (V3.0 — binding, do NOT regress)

The volume series module underwent a major simplification from V2.1 → V3.0. Key decisions:

### 9.1 Unified resolution pattern (the core simplification)
Every trade resolves volume the SAME way: `trade_volume = VolumeSeries_interval.volume × VolumeReference.multiplier`. No category branching, no separate code paths. The distinction between PPAs and DA trades is entirely in data properties, not in resolution logic.

- **PPA (generation-following):** `VolumeReference` → shared asset `VolumeSeries(seriesType=FORECAST)`, `multiplier=0.30`, `meteredSeriesKey` set
- **DA/bilateral (fixed-profile):** `VolumeReference` → per-trade `VolumeSeries(seriesType=PROFILE)`, `multiplier=1.0`, `meteredSeriesKey=null` (settlement uses same PROFILE volume)
- Fixed-profile is a **degenerate case** — same pattern as fixed price being a degenerate PriceExpression (D-2)

### 9.2 Entities (V3.0 final)
- `VolumeSeries` — unified root aggregate (replaces `ForecastVolumeSeries` + `ContractualVolumeSeries`); has `seriesType` enum (FORECAST | PROFILE); exactly one of `assetId` / `tradeLegId` set
- `VolumeInterval` — unified interval entity (replaces `ForecastInterval` + `ContractualInterval`)
- `MeteredActualVolumeSeries` + `MeteredActualInterval` — unchanged, per asset only, TSO-sourced
- `VolumeReference` — universal link from trade-leg to volume (replaces `AssetVolumeReference` which was PPA-only); carries `multiplier`, `effectiveFrom/To` (own date range), `volumeSeriesKey`, `meteredSeriesKey` (nullable)
- `CompactionView` — optional coarsened read model, not source of truth

### 9.3 What was eliminated and why
- **Category A/B distinction** — eliminated; one code path for all trades
- **ContractualVolumeSeries** (as separate entity) — merged into VolumeSeries with `seriesType=PROFILE`; PPAs never had intervals anyway (multiplier × forecast IS the contractual volume)
- **Separate ForecastInterval / ContractualInterval tables** — unified into `volume_interval`
- **PLAN bucket** (V2.1) — removed entirely; valuation consumes FORECAST for undelivered intervals
- **CascadeTier** (V2.1) — removed; store-as-uploaded + optional compaction

### 9.4 Asset-centric ownership (binding)
- FORECAST series: per asset (shared across N trade-legs via multiplier)
- METERED_ACTUAL series: per asset (shared, TSO meter on physical connection point)
- PROFILE series: per trade-leg (dedicated, few intervals, created once at capture)
- Multiplier applied at **consumption time** (read path), never stored on volume intervals. One forecast update = one write = N revaluations via fan-out.
- Sum of multipliers per asset per time window should ≤ 1.0 (business validation, not hard constraint)

### 9.5 Consumption contract (position/valuation reads volume)
Single query: `queryVolumeForTradeLeg(tradeLegId, purpose, intervalRange)` where purpose = FORWARD | SETTLEMENT.
- FORWARD: resolves `VolumeReference.volumeSeriesKey` × multiplier
- SETTLEMENT: resolves `VolumeReference.meteredSeriesKey` × multiplier (if set); else falls back to `volumeSeriesKey` × multiplier
- Consumer handles granularity expansion (stored grain → atomic grid)
- Version stamping per FR-056: every valuation cell stamps `(series_key, version_id)` used

### 9.6 VolumeReference date range (effectiveFrom/To on reference, NOT derived from trade)
Five reasons: (1) stepped allocations (different multipliers over time), (2) partial unwinds, (3) sub-range coverage (asset connects later than trade starts), (4) query efficiency for fan-out, (5) independent lifecycle from trade amendments. Validation rule: allocation period must be within trade delivery window (soft check).

---

## 10. Fixed decisions (D-1…D-11) — cite these, don't re-argue them

D-1 month-block ledger grain, signed qty, no direction enum, no interval fan-out in S1 · D-2 price = expression ref, fixed = degenerate · D-3 forward ephemeral / settlement bitemporal / official MtM = month-bucket EOD strike with stamps · D-4 optimized restatement binding + active_leaves at first resolution · D-5 peak = interval dimension, calendar-versioned · D-6 dual units only in cache/rollups · D-7 batch authoritative, events additive; re-derive idempotency · D-8 netting = projection policy; drill-down mandatory · D-9 shared monthly partitions, tenant leading key; S5c on strike-month axis · D-10 all interval structure via MarketCalendar · **D-11 unified volume resolution: every trade via VolumeReference → VolumeSeries × multiplier; fixed-profile = degenerate case (multiplier=1.0, per-trade PROFILE series); no category branching in code**.

## 11. Open items (O-1…O-8) — resolve before/during tech spec, do not guess

O-1 hot-window length per market · O-2 event path in phase 1 vs batch-only (biggest scope lever) · O-3 v1 expression operator set · O-4 settlement cell: price vs amount vs both (cashflow-module contract) · O-5 struck-mark bucket granularity confirmation · O-6 curve-pillar→interval expansion ownership (affects meaning of "curve version" stamp) · O-7 dispute/annotation model on settlement cells · O-8 cross-zone financial-net flagging in payloads.

## 12. Platform constants (from prior architecture work — apply here too)

Java 21 / Spring Boot 3.3 / Aurora PostgreSQL 16 (NO TimescaleDB — invalid on RDS/Aurora; declarative partitioning + pg_partman + pg_cron + matviews) / Kafka 3.7 KRaft (batch listeners for high-volume consumers, manual commit, InFlightOffsetTracker at-least-once) / Redis 7 (pipeline + MULTI-EXEC compose independently) / `GenerationType.SEQUENCE` allocationSize=50 (never IDENTITY — kills JDBC batching) / Flyway owns all DDL incl. partition mgmt / per-tenant Hikari partitioning + PgBouncer transaction pooling + WFQ producer-side token buckets for noisy-neighbour isolation.

---

**Status:** Functional spec v1.0 in review. Volume Series spec V3.0 (unified model) complete. Technical spec NOT yet authored — explicitly deferred until functional review completes. Implementation prompts should cite `functional-spec-position-valuation-v1.0.md` FR/D numbers, `VOLUME_SERIES_SPEC-V3_0.md` for volume model, and this file for rationale.
