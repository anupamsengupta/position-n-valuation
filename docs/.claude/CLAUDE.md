# Project Context — Position & Valuation System

## Project overview
Multitenant SaaS CTRM for EU power (EPEX/EEX/NORDPOOL/XBID), ~200 tenants. Covers position generation, price expression, valuation, and volume series management. Design phase — no application code yet, only specs and documentation.

## Repository structure
```
docs/
  functional-spec/
    functional-spec-position-valuation-v1.0.md   # Binding spec (FR-nnn rules, D-1…D-12, O-1…O-8)
  functional-spec/
    VOLUME_SERIES_SPEC-V3_0.md                    # Volume series domain model (unified V3.0)
  spec-in-layman-language/
    README.md                                     # Plain-language guide (~1100 lines)
  context/
    CONTEXT-position-valuation-design.md          # Design rationale & decisions
```

## Key design decisions (do NOT regress)
- **D-1:** Ledger grain = trade-leg × delivery-month block; signed qty; no interval fan-out in S1
- **D-2:** Price = expression ref; fixed price = degenerate expression
- **D-3:** Forward marks ephemeral; settlement bitemporal; EOD strike = month-bucket with stamps
- **D-11:** Unified volume resolution: VolumeReference → VolumeSeries × multiplier for ALL trades
- **D-12:** S6b trade_interval_cache: optional, rebuildable, commodity-neutral
- Full list: D-1…D-12 in context doc §10

## Architecture (S1–S8 + S6b)
- **S1** Position Ledger — bitemporal entity, sparse (~24 rows/yr-deal)
- **S2** PriceExpression — versioned tree; fixed price = degenerate
- **S3** VolumeSeries (V3.0 unified) — VolumeReference × multiplier; FORECAST per asset / PROFILE per trade
- **S4** Market Data Store — shared reference facts
- **S5a** Settlement cells — durable bitemporal measure
- **S5b** Forward marks — EPHEMERAL current-state only
- **S5c** EOD struck marks — durable immutable frozen projection
- **S6** Slot Cache — rebuildable netted materialization (net across trades)
- **S6b** Trade Interval Cache — optional per-trade pre-multiplied volume (portfolio detail)
- **S7** Rollups — rebuildable; far-dated + reporting
- **S8** Dependency index — active_leaves blast-radius optimization

## Volume model (V3.0 — unified)
- Every trade: `trade_volume = volume_interval.volume × volume_reference.multiplier`
- PPA: shared FORECAST series per asset, multiplier < 1.0
- DA/bilateral: dedicated PROFILE series per trade, multiplier = 1.0 (degenerate case)
- MeteredActualVolumeSeries: per asset, TSO-sourced, unchanged
- VolumeReference has own effectiveFrom/To (not derived from trade)

## Platform constants
Java 21 / Spring Boot 3.3 / Aurora PostgreSQL 16 (NO TimescaleDB) / Kafka 3.7 KRaft / Redis 7 / pg_partman + pg_cron / Flyway DDL / per-tenant Hikari + PgBouncer

## Working conventions
- Cite FR-nnn / D-nn numbers when referencing spec rules
- Reference deal: T-7788, tenant TN_0042, EPEX DE_LU wind PPA
- Context doc explains rationale; functional spec is binding
- Layman README is for non-technical stakeholders (product, QA, ops)
- Technical spec NOT yet authored — deferred until functional review completes

## Multi-commodity extensibility
The core model is commodity-neutral. `VolumeReference × multiplier` pattern works for:
- **Gas:** storage facility withdrawal rights (multiplier = facility share)
- **Oil:** consortium lifting (multiplier = participant share)
- **Ags:** cooperative pool allocation (multiplier = farmer share)
Only interval semantics and unit duality change per commodity.
