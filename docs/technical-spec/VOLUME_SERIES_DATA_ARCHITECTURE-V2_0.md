# Volume Series Data Architecture — V2.0

| Field | Value |
|---|---|
| **Version** | 2.0 |
| **Status** | Draft |
| **Companion Specs** | `VOLUME_SERIES_SPEC-V3_0.md` (volume domain), `functional-spec-position-valuation-v1.0.md` (position/valuation) |
| **Supersedes** | `VOLUME_SERIES_DATA_ARCHITECTURE-V1_3.md` |
| **Date** | 2026-07-22 |

---

## Table of Contents

1. [Scope & Breaking Changes from V1.3](#1-scope--breaking-changes-from-v13)
2. [Architecture Principles](#2-architecture-principles)
3. [Database Topology](#3-database-topology)
4. [Schema Structure](#4-schema-structure)
5. [DDL — All Domain Tables](#5-ddl--all-domain-tables)
6. [Partitioning Strategy](#6-partitioning-strategy)
7. [Index Strategy](#7-index-strategy)
8. [Storage & Compression](#8-storage--compression)
9. [Retention Strategy](#9-retention-strategy)
10. [Bi-Temporal Audit](#10-bi-temporal-audit)
11. [Multitenancy Enforcement](#11-multitenancy-enforcement)
12. [Cache Strategy (Redis)](#12-cache-strategy-redis)
13. [Event Integration](#13-event-integration)
14. [SLA Targets](#14-sla-targets)
15. [Backup & DR](#15-backup--dr)
16. [Migration Strategy](#16-migration-strategy)
17. [Operational Notes](#17-operational-notes)
18. [Position Persistence — Scope Boundary](#18-position-persistence--scope-boundary)

---

## 1. Scope & Breaking Changes from V1.3

This document specifies the persistence layer for the **volume series** domain — the data structures backing `VolumeSeries`, `VolumeInterval`, `VolumeReference`, `MeteredActualVolumeSeries`, and supporting entities defined in `VOLUME_SERIES_SPEC-V3_0.md`.

V2.0 is a **complete rewrite**, not an incremental patch. The V1.3 document was built on the superseded V2.1 domain model and is fundamentally incompatible with the current V3.0 architecture.

### 1.1 Breaking Change Summary

| V1.3 Concept | V2.0 Status | Reason |
|---|---|---|
| RDS PostgreSQL + TimescaleDB | **Removed** | Aurora PostgreSQL 16 mandated (§12, context doc). TimescaleDB is not available on Aurora. |
| `bucket_type` enum (CONTRACTUAL / PLAN / ACTUAL) | **Removed** | Replaced by `series_type` (FORECAST / PROFILE) on `volume_series` + separate `metered_actual_volume_series` table |
| `cascade_tier` enum (NEAR_TERM / MEDIUM_TERM / LONG_TERM) | **Removed** | V3.0 stores intervals as-uploaded; no cascade tiers |
| Wide-table scalars (14 named columns + JSONB `custom_scalars`) | **Removed** | These belong in the valuation layer (S5a/S5b/S5c), not in volume intervals |
| BAV continuous aggregate (§11 in V1.3) | **Removed** | Replaced by `VolumeReference × multiplier` resolution (D-11) |
| Position as continuous aggregate (§18 in V1.3) | **Removed** | Position Ledger (S1) is a bitemporal source-of-truth entity (D-1, D-8) |
| Cross-cluster integration (§3.4 in V1.3) | **Removed** | Single Aurora cluster; no separate TimescaleDB cluster |
| `net_position` continuous aggregate | **Removed** | Owned by position/valuation module, not volume series |
| `hypertable`, `drop_chunks`, `compress_chunk` | **Removed** | All TimescaleDB-specific DDL replaced by pg_partman equivalents |

### 1.2 What Is New in V2.0

| Concept | Source |
|---|---|
| `volume_reference` table | D-11: universal trade-leg → volume link with multiplier |
| `series_type` enum (FORECAST / PROFILE) | V3.0 unified model |
| `volume_layer` enum (VOLUME / METERED_ACTUAL) | V3.0 event payloads |
| `quality_state` enum (7 values) | V3.0 lifecycle states |
| `series_key` on `volume_series` | Stable external key surviving amendments |
| `asset_id` XOR `trade_leg_id` constraint | V3.0: FORECAST per-asset, PROFILE per-trade-leg |
| Separate `metered_actual_volume_series` + `metered_actual_interval` | V3.0: meter data is a distinct aggregate |
| pg_partman monthly partitions | Aurora-compatible replacement for TimescaleDB chunks |
| `trade_interval_cache` (S6b) | D-12: optional pre-multiplied reporting cache |

### 1.3 What Is Preserved from V1.3

- Document structure and DDL formatting style
- Outbox pattern for trade events (§13)
- Idempotent consumer design (§13.3)
- Forward-link supersession pattern on intervals
- Hibernate batch insert configuration (§17.1)
- Timezone handling at DB boundary (§17.3)
- RLS-based multitenancy enforcement (§11)
- Flyway migration strategy (§16)
- Numeric precision conventions (§5)
- Sequence-based ID generation with `allocationSize=50` (P3)
- Dual HikariCP read/write pool separation (P4)

---

## 1.4 Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.3 |
| Database | Aurora PostgreSQL | 16 |
| Messaging | Kafka (KRaft mode) | 3.7 |
| Cache | Redis | 7 |
| Partitioning | pg_partman + pg_cron | — |
| Migrations | Flyway | — |
| Connection pool | HikariCP + PgBouncer | — |

---

## 2. Architecture Principles

### P0 — Single Aurora PostgreSQL 16 Cluster

All volume series data resides in a single Aurora PostgreSQL 16 cluster. No separate RDS instance, no TimescaleDB extension.

**Rationale:** Aurora PostgreSQL 16 provides storage auto-scaling, up to 15 read replicas, automatic failover, and I/O-optimized pricing. TimescaleDB is not available on Aurora. pg_partman provides equivalent time-based partitioning with monthly partition drop for retention.

### P1 — Multitenancy via Discriminator

Every domain table carries `tenant_id` as the leading isolation key. Enforcement is three-layered:

1. `@TenantAware` aspect sets `app.tenant_id` session variable on every request
2. RLS policy on every domain table filters by `current_setting('app.tenant_id')`
3. `tenant_id` is the lead column on every composite index

Large tenants are promotable to dedicated Aurora clusters with zero schema changes.

### P2 — Immutability Rules per Series Type

| Series Type | Rule | Enforcement |
|---|---|---|
| PROFILE | Immutable after capture — no updates, no deletes except retention purge | DB trigger |
| FORECAST | Supersession — new version inserted, old version marked SUPERSEDED; old rows never modified | Application + quality_state check |
| METERED_ACTUAL | Append-only — corrections insert new rows with `supersedes_id`; original rows never touched | DB trigger |

### P3 — Native Sequences with allocationSize=50

All internal IDs use `GenerationType.SEQUENCE` with `allocationSize=50`. `IDENTITY` columns are prohibited — they kill Hibernate JDBC batch inserts by requiring per-row round-trips.

### P4 — Read/Write Split via Dual HikariCP

Two connection pools per service instance, routed at the DataSource bean level:

| Pool | Aurora Endpoint | Purpose |
|---|---|---|
| `writeDataSource` | Writer (single instance) | All INSERT/UPDATE/DELETE |
| `readDataSource` | Reader (load-balanced across replicas) | All SELECT for read-heavy queries |

Not enforced via `@Transactional(readOnly=true)` — Spring Data JPA routes those to the writer in some configurations.

### P5 — DB-Level Constraints Where Feasible

CHECK constraints for cheap invariants (delivery window, tolerance bands, month ranges). Complex cross-entity invariants (e.g., multiplier soft-cap across volume references per asset) enforced at application layer.

### P6 — Retention via Partition Drop

No row-level DELETE at scale. Retention is achieved by dropping entire monthly partitions via pg_partman + pg_cron. Partition drop is a metadata operation — O(1), no WAL generation, no VACUUM required.

### P7 — Hot/Warm/Cold Tiering

| Tier | Window | Characteristics |
|---|---|---|
| HOT | Today → T+60d + current delivery month | Slot cache + S6b populated; forward marks live; event-driven updates; Redis-cached |
| WARM | Delivered months within 12–14 month regulatory window | Settlement cells durable; no slot cache; no forward marks; audit/recon/disputes |
| COLD | Beyond regulatory window, within 7-year retention | Archived to S3/Glacier; derived layers re-derivable from archived S1/S2/S4 |
| PURGE | >7 years post-settlement | Monthly partitions dropped; no data retained (GDPR) |

### P8 — Unified Volume Resolution (D-11)

Every trade resolves volume through: `trade_volume = volume_interval.volume × volume_reference.multiplier`

- Asset-linked (PPA): multiplier ∈ (0, 1] — trade gets a share of the shared FORECAST series
- Fixed-profile (DA/bilateral): multiplier = 1.0 — degenerate case; dedicated per-trade PROFILE series

No category branching in code. One resolution path for all trades.

---

## 3. Database Topology

### 3.1 Aurora Cluster Configuration

| Component | Configuration |
|---|---|
| Engine | Aurora PostgreSQL 16 |
| Pricing | I/O-Optimized (included I/O, no per-request charge) |
| Writer instance | `db.r7g.2xlarge` (8 vCPU, 64 GB RAM) |
| Reader instances | 2 × `db.r7g.xlarge` (4 vCPU, 32 GB RAM) |
| Multi-AZ | Yes — writer + standby in different AZ; automatic failover < 30s |
| Storage | Aurora auto-scaling, 10 GB initial, grows in 10 GB increments |
| Max storage | 128 TB |
| Encryption | AES-256, AWS KMS managed |
| Parameter group | Custom (see §17.5) |

**Why I/O-Optimized:** Volume interval writes are high-throughput (batch inserts of 1,000–3,000 rows). Standard Aurora charges per I/O request; at our write volume (~50M+ rows/month across tenants), I/O-Optimized is cost-effective above ~$500/month I/O spend.

### 3.2 Cost Model (Annual Estimate)

| Component | Spec | Monthly Cost (est.) |
|---|---|---|
| Writer `db.r7g.2xlarge` (I/O-Opt) | On-demand | ~$1,800 |
| 2 × Reader `db.r7g.xlarge` (I/O-Opt) | On-demand | ~$1,800 |
| Storage (500 GB year-1, growing ~100 GB/yr) | $0.225/GB-month (includes backup) | ~$112 |
| Backups (beyond free tier) | $0.021/GB-month | ~$10 |
| **Year-1 total** | | **~$44,600** |

Reserved instances (1-year, partial upfront) reduce compute cost by ~35%.

### 3.3 Schema Organization

```
aurora_cluster/
├── volume_series          -- schema: all volume domain tables
├── volume_audit           -- schema: audit/history tables
├── trade                  -- schema: outbox + saga (trade service)
└── public                 -- schema: pg_partman management tables
```

All domain tables live in the `volume_series` schema. Audit tables in `volume_audit`. The `trade` schema holds the outbox and saga state tables used by the trade capture service.

### 3.4 Connection Pool Configuration

| Pool | Endpoint | Max Size | Min Idle | Connection Timeout | Idle Timeout | Max Lifetime |
|---|---|---|---|---|---|---|
| `writeDataSource` | Writer | 20 | 5 | 3000 ms | 600,000 ms | 1,800,000 ms |
| `readDataSource` | Reader | 30 | 10 | 3000 ms | 600,000 ms | 1,800,000 ms |

`leakDetectionThreshold`: 60,000 ms.

Reader pool is larger because analytical queries are longer-running and read traffic dominates (~80% reads). `connectionTimeout=3000ms` enforces fast-fail. Aurora reader endpoint auto-balances across replicas — no application-side load balancing needed.

Per-tenant Hikari partitioning + PgBouncer transaction pooling + WFQ (weighted fair queuing) producer-side token buckets for noisy-neighbour isolation.

---

## 4. Schema Structure

### 4.1 ER Diagram (V3.0 Entities)

```
                    ┌─────────────────────────────────────────────┐
                    │            volume_reference                 │
                    │  ─────────────────────────────────────────  │
                    │  id (PK)                                    │
                    │  trade_leg_id          (always set)         │
                    │  trade_id              (denormalized)       │
                    │  asset_id              (nullable)           │
                    │  multiplier            (0,1] or 1.0         │
                    │  volume_series_key     → volume_series      │
                    │  metered_series_key    → metered_actual     │
                    │  effective_from / to                        │
                    └──────────┬──────────────────┬───────────────┘
                               │                  │
                    ┌──────────▼──────────┐  ┌────▼──────────────────────┐
                    │   volume_series     │  │ metered_actual_volume_    │
                    │  ────────────────── │  │ series                    │
                    │  id (PK)            │  │ ──────────────────────    │
                    │  series_key (UQ)    │  │ id (PK)                   │
                    │  series_type        │  │ series_key (UQ)           │
                    │  asset_id XOR       │  │ asset_id                  │
                    │   trade_leg_id      │  │ version_id                │
                    │  version_id         │  │ quality_state             │
                    │  quality_state      │  │ ...                       │
                    │  ...                │  └────────┬──────────────────┘
                    └────────┬───────────┘           │
                             │                       │
                    ┌────────▼───────────┐  ┌────────▼──────────────────┐
                    │  volume_interval   │  │  metered_actual_interval  │
                    │  ──────────────    │  │  ────────────────────     │
                    │  id (PK)           │  │  id (PK)                  │
                    │  series_id (FK)    │  │  series_id (FK)           │
                    │  interval_start    │  │  interval_start           │
                    │  volume, energy    │  │  volume, energy           │
                    │  status            │  │  quality_state            │
                    │  PARTITIONED BY    │  │  PARTITIONED BY           │
                    │   interval_start   │  │   interval_start          │
                    └────────────────────┘  └───────────────────────────┘

                    ┌────────────────────┐
                    │  volume_formula    │       ┌─────────────────────┐
                    │  ──────────────    │──────▶│  shaping_entry      │
                    │  id (PK)           │       └─────────────────────┘
                    │  reference_id (FK  │       ┌─────────────────────┐
                    │   → volume_ref)    │──────▶│  seasonal_adjustment│
                    └────────────────────┘       └─────────────────────┘

                    ┌────────────────────────────────────────┐
                    │  materialization_chunk_status           │
                    │  ──────────────────────────────         │
                    │  series_id (FK), chunk_month, status    │
                    └────────────────────────────────────────┘

                    ┌─────────────────────┐     ┌─────────────────────┐
                    │  compaction_view     │────▶│  compacted_interval │
                    └─────────────────────┘     └─────────────────────┘
```

### 4.2 Table Inventory with Row Estimates

Assumptions: T=200 tenants, A=8 assets/tenant, N=2.5 trade-legs/asset, P=T×A×N=4,000 PPAs, Bf=5 fixed-profile/tenant=1,000 DA trades, M=12 hot months, D=30.4 days/month, I=96 intervals/day (15-min), Ih=24 intervals/day (hourly).

| Table | Steady-State Hot Rows | Growth Rate |
|---|---|---|
| `volume_series` | ~10K (current versions) | ~2K/month (new forecasts + profiles) |
| `volume_interval` (FORECAST, 15-min) | ~56M | ~4.7M/month |
| `volume_interval` (PROFILE) | ~35M | ~2.9M/month |
| `metered_actual_volume_series` | ~1.6K | ~100/month |
| `metered_actual_interval` (6 months) | ~28M | ~4.7M/month |
| `volume_reference` | ~5K | ~200/month |
| `volume_formula` | ~5K | ~200/month |
| `shaping_entry` | ~15K | ~600/month |
| `seasonal_adjustment` | ~5K | ~200/month |
| `materialization_chunk_status` | ~20K | ~2K/month |
| `compaction_view` | ~2K | ~200/month |
| `compacted_interval` | ~5M | ~400K/month |
| `trade_interval_cache` (S6b, 2-month window) | ~29M | Rebuilt, not growing |
| **Hot total** | **~77M–119M** | |

**Rows eliminated by unified model** (vs V1.3 per-trade duplication):
- Contractual intervals for PPAs: ~140M rows eliminated
- Per-trade forecast copies: ~21M rows saved
- Per-trade meter copies: ~42M rows saved

---

## 5. DDL — All Domain Tables

### 5.0 Numeric Precision Conventions

| Domain | PostgreSQL Type | Example |
|---|---|---|
| Volume (MW capacity) | `NUMERIC(15, 6)` | 128.400000 |
| Energy (MWh) | `NUMERIC(18, 6)` | 11.400000 |
| Multiplier | `NUMERIC(8, 6)` | 0.300000 |
| Price (EUR/MWh) | `NUMERIC(12, 4)` | 68.2000 |
| Cost/amount (EUR) | `NUMERIC(15, 4)` | 777.4800 |

All internal IDs: `BIGINT` with sequence (`allocationSize=50`). External/API-facing IDs: `UUID`.
`materialized_through`: stored as `DATE` (first day of month) — PostgreSQL has no native `YearMonth` type.

### 5.1 Sequences

```sql
CREATE SEQUENCE volume_series.volume_series_seq        START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.volume_interval_seq      START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.volume_reference_seq     START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.volume_formula_seq       START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.shaping_entry_seq        START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.seasonal_adjustment_seq  START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.metered_series_seq       START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.metered_interval_seq     START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.chunk_status_seq         START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.compaction_view_seq      START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.compacted_interval_seq   START 1 INCREMENT 50;
CREATE SEQUENCE volume_series.trade_interval_cache_seq START 1 INCREMENT 50;
```

### 5.2 Enum Types

```sql
-- V3.0 series classification (replaces V1.3 bucket_type)
CREATE TYPE volume_series.series_type AS ENUM ('FORECAST', 'PROFILE');

-- V3.0 volume layer (for events and audit)
CREATE TYPE volume_series.volume_layer AS ENUM ('VOLUME', 'METERED_ACTUAL');

-- Interval width
CREATE TYPE volume_series.time_granularity AS ENUM (
    'MIN_5', 'MIN_15', 'MIN_30', 'HOURLY', 'DAILY', 'MONTHLY'
);

-- Delivery profile shape
CREATE TYPE volume_series.profile_type AS ENUM (
    'BASELOAD', 'PEAKLOAD', 'OFFPEAK', 'SHAPED', 'BLOCK', 'GENERATION_FOLLOWING'
);

-- V3.0 lifecycle states (replaces simple version-based supersession)
CREATE TYPE volume_series.quality_state AS ENUM (
    'EFFECTIVE',    -- PROFILE: active trade obligation
    'AMENDED',      -- PROFILE: superseded by trade amendment
    'CURRENT',      -- FORECAST: latest forecast version
    'SUPERSEDED',   -- FORECAST: replaced by newer forecast
    'PROVISIONAL',  -- METERED_ACTUAL: initial meter read (D+1)
    'VALIDATED',    -- METERED_ACTUAL: confirmed by TSO
    'ESTIMATED'     -- METERED_ACTUAL: gap-filled estimate
);

-- Materialization progress
CREATE TYPE volume_series.materialization_status AS ENUM (
    'PENDING', 'PARTIAL', 'FULL', 'FAILED'
);

-- Individual interval state
CREATE TYPE volume_series.interval_status AS ENUM (
    'CONFIRMED', 'ESTIMATED', 'PROVISIONAL', 'CANCELLED'
);

-- Volume interpretation
CREATE TYPE volume_series.volume_unit AS ENUM ('MW_CAPACITY', 'MWH_PER_PERIOD');

-- Chunk processing state
CREATE TYPE volume_series.chunk_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'DLQ'
);
```

### 5.3 volume_series — Unified Root Entity

```sql
CREATE TABLE volume_series.volume_series (
    id                          BIGINT PRIMARY KEY
                                    DEFAULT nextval('volume_series.volume_series_seq'),
    series_uuid                 UUID NOT NULL UNIQUE,
    tenant_id                   UUID NOT NULL,

    -- Stable external key (survives amendments)
    series_key                  VARCHAR(128) NOT NULL,

    -- V3.0 series classification
    series_type                 volume_series.series_type NOT NULL,

    -- Ownership: exactly one must be non-null
    asset_id                    VARCHAR(64),        -- set for FORECAST
    trade_leg_id                VARCHAR(64),        -- set for PROFILE
    trade_id                    VARCHAR(64),        -- denormalized; set for PROFILE
    trade_version               INTEGER,            -- optimistic lock; set for PROFILE

    -- Version chain
    version_id                  BIGINT NOT NULL,    -- monotonically increasing per (series_key, VOLUME layer)

    -- Interval structure
    volume_unit                 volume_series.volume_unit NOT NULL,
    granularity                 volume_series.time_granularity NOT NULL,
    profile_type                volume_series.profile_type NOT NULL,

    -- Delivery window
    delivery_start              TIMESTAMPTZ NOT NULL,
    delivery_end                TIMESTAMPTZ NOT NULL,
    delivery_timezone           VARCHAR(64) NOT NULL,   -- e.g. 'Europe/Berlin'

    -- FORECAST-only fields
    forecast_source_id          VARCHAR(128),       -- null for PROFILE
    rated_capacity_mw           NUMERIC(15, 6),     -- asset nameplate; null for PROFILE
    published_at                TIMESTAMPTZ,        -- null for PROFILE

    -- Lifecycle
    quality_state               volume_series.quality_state NOT NULL,

    -- Materialization tracking
    materialization_status      volume_series.materialization_status NOT NULL,
    materialized_through        DATE,               -- first day of month (no native YearMonth)
    total_expected_intervals    INTEGER NOT NULL,
    materialized_interval_count INTEGER NOT NULL DEFAULT 0,

    -- Bi-temporal audit
    transaction_time            TIMESTAMPTZ NOT NULL,   -- when system recorded this version
    valid_time                  TIMESTAMPTZ,            -- when economically effective (set for PROFILE)

    -- Housekeeping
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT chk_vs_delivery_window
        CHECK (delivery_end > delivery_start),
    CONSTRAINT chk_vs_interval_count
        CHECK (materialized_interval_count <= total_expected_intervals),
    CONSTRAINT chk_vs_ownership_xor
        CHECK (
            (asset_id IS NOT NULL AND trade_leg_id IS NULL)      -- FORECAST
            OR (asset_id IS NULL AND trade_leg_id IS NOT NULL)   -- PROFILE
        ),
    CONSTRAINT chk_vs_type_ownership
        CHECK (
            (series_type = 'FORECAST' AND asset_id IS NOT NULL)
            OR (series_type = 'PROFILE' AND trade_leg_id IS NOT NULL)
        ),
    CONSTRAINT chk_vs_quality_state_type
        CHECK (
            (series_type = 'FORECAST' AND quality_state IN ('CURRENT', 'SUPERSEDED'))
            OR (series_type = 'PROFILE' AND quality_state IN ('EFFECTIVE', 'AMENDED'))
        )
);

-- Unique: one CURRENT version per series_key
CREATE UNIQUE INDEX uq_vs_series_key_current
    ON volume_series.volume_series (tenant_id, series_key)
    WHERE quality_state IN ('CURRENT', 'EFFECTIVE');

-- For PROFILE: one EFFECTIVE version per trade-leg
CREATE UNIQUE INDEX uq_vs_trade_leg_effective
    ON volume_series.volume_series (tenant_id, trade_id, trade_leg_id, trade_version)
    WHERE series_type = 'PROFILE' AND quality_state = 'EFFECTIVE';

ALTER TABLE volume_series.volume_series ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_series
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.4 volume_interval — Partitioned by interval_start

```sql
CREATE TABLE volume_series.volume_interval (
    id                  BIGINT NOT NULL
                            DEFAULT nextval('volume_series.volume_interval_seq'),
    interval_uuid       UUID NOT NULL,
    series_id           BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,

    -- Time range
    interval_start      TIMESTAMPTZ NOT NULL,
    interval_end        TIMESTAMPTZ NOT NULL,

    -- Values
    volume              NUMERIC(15, 6) NOT NULL,   -- MW or MWh per volumeUnit
    energy              NUMERIC(18, 6) NOT NULL,   -- derived MWh; scale 6, HALF_UP (D-6: stored at source for cache/rollup efficiency)

    -- State
    status              volume_series.interval_status NOT NULL,
    chunk_month         DATE,                      -- set for PROFILE rolling-horizon chunks

    -- Forward-link append-only versioning
    version             INTEGER NOT NULL DEFAULT 1,
    supersedes_id       BIGINT,

    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT chk_vi_window
        CHECK (interval_end > interval_start),
    CONSTRAINT chk_vi_supersedes_versioning
        CHECK (
            (supersedes_id IS NULL AND version = 1)
            OR (supersedes_id IS NOT NULL AND version > 1)
        ),

    PRIMARY KEY (id, interval_start)
) PARTITION BY RANGE (interval_start);

ALTER TABLE volume_series.volume_interval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_interval
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

**Note:** `series_id` is a logical FK to `volume_series.id`. Declarative FK constraints on partitioned tables are supported in PostgreSQL 12+ but carry overhead on partition-heavy configurations. The FK is enforced at the application layer via Hibernate's `@ManyToOne`.

### 5.5 volume_reference — The D-11 Universal Link

```sql
CREATE TABLE volume_series.volume_reference (
    id                  BIGINT PRIMARY KEY
                            DEFAULT nextval('volume_series.volume_reference_seq'),
    reference_uuid      UUID NOT NULL UNIQUE,
    tenant_id           UUID NOT NULL,

    -- Trade linkage (always set)
    trade_leg_id        VARCHAR(64) NOT NULL,
    trade_id            VARCHAR(64) NOT NULL,       -- denormalized for queries

    -- Asset linkage (set for asset-linked trades; null for fixed-profile)
    asset_id            VARCHAR(64),

    -- Volume resolution
    multiplier          NUMERIC(8, 6) NOT NULL,     -- (0, 1] for asset-linked; 1.0 for fixed-profile
    volume_series_key   VARCHAR(128) NOT NULL,       -- FK to volume_series.series_key
    metered_series_key  VARCHAR(128),                -- FK to metered_actual_volume_series.series_key
                                                     --   null for exchange/bilateral trades

    -- Allocation window (own date range, not derived from trade)
    effective_from      TIMESTAMPTZ NOT NULL,
    effective_to        TIMESTAMPTZ NOT NULL,

    -- Housekeeping
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT chk_vr_effective_window
        CHECK (effective_to > effective_from),
    CONSTRAINT chk_vr_multiplier_range
        CHECK (multiplier > 0 AND multiplier <= 1.0),
    CONSTRAINT chk_vr_asset_metered_consistency
        CHECK (
            -- Asset-linked with meter connected: asset_id AND metered_series_key both set
            (asset_id IS NOT NULL AND metered_series_key IS NOT NULL)
            -- OR asset-linked, meter not yet connected (PPA registered before TSO meter link)
            OR (asset_id IS NOT NULL AND metered_series_key IS NULL)
            -- OR fixed-profile: both null
            OR (asset_id IS NULL AND metered_series_key IS NULL)
        )
);

ALTER TABLE volume_series.volume_reference ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_reference
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

**Multiplier semantics:**
- Asset-linked (PPA): multiplier ∈ (0, 1] — trade gets a share of the shared FORECAST series
- Fixed-profile (DA/bilateral): multiplier = 1.0 — degenerate case
- Soft validation: sum of active multipliers per asset should not exceed 1.0. Over-allocation flagged as warning, not error. Under-allocation is normal.

### 5.6 volume_formula — Contract-Level Parameters

```sql
CREATE TABLE volume_series.volume_formula (
    id                  BIGINT PRIMARY KEY
                            DEFAULT nextval('volume_series.volume_formula_seq'),
    formula_uuid        UUID NOT NULL UNIQUE,
    reference_id        BIGINT NOT NULL UNIQUE,     -- 1:1 FK to volume_reference
    tenant_id           UUID NOT NULL,

    -- Tolerance band
    base_volume         NUMERIC(15, 6),
    min_volume          NUMERIC(15, 6),
    max_volume          NUMERIC(15, 6),

    -- Forecast parameters
    forecast_source_id  VARCHAR(128),
    forecast_multiplier NUMERIC(8, 6),

    -- Calendar reference
    calendar_id         VARCHAR(64),

    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_formula_reference FOREIGN KEY (reference_id)
        REFERENCES volume_series.volume_reference(id) ON DELETE CASCADE,
    CONSTRAINT chk_vf_tolerance_band CHECK (
        (min_volume IS NULL AND max_volume IS NULL)
        OR (min_volume IS NOT NULL AND max_volume IS NOT NULL AND min_volume <= max_volume)
    )
);

ALTER TABLE volume_series.volume_formula ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.volume_formula
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.7 shaping_entry

```sql
CREATE TABLE volume_series.shaping_entry (
    id                  BIGINT PRIMARY KEY
                            DEFAULT nextval('volume_series.shaping_entry_seq'),
    formula_id          BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,

    applicable_days     SMALLINT NOT NULL,       -- bitmask: Mon=1, Tue=2, Wed=4, Thu=8,
                                                 --          Fri=16, Sat=32, Sun=64
    block_start         TIME NOT NULL,
    block_end           TIME NOT NULL,
    volume              NUMERIC(15, 6) NOT NULL,
    applies_to_holidays BOOLEAN NOT NULL DEFAULT false,
    valid_from_month    SMALLINT,                -- 1..12 or null
    valid_to_month      SMALLINT,

    CONSTRAINT fk_shaping_formula FOREIGN KEY (formula_id)
        REFERENCES volume_series.volume_formula(id) ON DELETE CASCADE,
    CONSTRAINT chk_se_block_window CHECK (block_end > block_start),
    CONSTRAINT chk_se_month_range CHECK (
        (valid_from_month IS NULL AND valid_to_month IS NULL)
        OR (valid_from_month BETWEEN 1 AND 12 AND valid_to_month BETWEEN 1 AND 12)
    ),
    CONSTRAINT chk_se_applicable_days CHECK (applicable_days BETWEEN 0 AND 127)
);

ALTER TABLE volume_series.shaping_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.shaping_entry
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.8 seasonal_adjustment

```sql
CREATE TABLE volume_series.seasonal_adjustment (
    id              BIGINT PRIMARY KEY
                        DEFAULT nextval('volume_series.seasonal_adjustment_seq'),
    formula_id      BIGINT NOT NULL,
    tenant_id       UUID NOT NULL,

    from_month      SMALLINT NOT NULL,
    to_month        SMALLINT NOT NULL,
    from_year       INTEGER,            -- null = all years
    to_year         INTEGER,
    multiplier      NUMERIC(8, 6),
    absolute_adj    NUMERIC(15, 6),

    CONSTRAINT fk_sa_formula FOREIGN KEY (formula_id)
        REFERENCES volume_series.volume_formula(id) ON DELETE CASCADE,
    CONSTRAINT chk_sa_months CHECK (
        from_month BETWEEN 1 AND 12 AND to_month BETWEEN 1 AND 12
    ),
    CONSTRAINT chk_sa_years CHECK (
        (from_year IS NULL AND to_year IS NULL)
        OR (from_year IS NOT NULL AND to_year IS NOT NULL AND from_year <= to_year)
    ),
    CONSTRAINT chk_sa_has_adjustment CHECK (
        multiplier IS NOT NULL OR absolute_adj IS NOT NULL
    )
);

-- Application formula: adjustedVolume = (baseVolume × multiplier) + absoluteAdj

ALTER TABLE volume_series.seasonal_adjustment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.seasonal_adjustment
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.9 metered_actual_volume_series

```sql
CREATE TABLE volume_series.metered_actual_volume_series (
    id                      BIGINT PRIMARY KEY
                                DEFAULT nextval('volume_series.metered_series_seq'),
    series_uuid             UUID NOT NULL UNIQUE,
    tenant_id               UUID NOT NULL,

    series_key              VARCHAR(128) NOT NULL,      -- e.g. 'MTR-WP-NORDSEE'
    asset_id                VARCHAR(64) NOT NULL,
    version_id              BIGINT NOT NULL,            -- monotonically increasing per series_key

    -- Interval structure
    volume_unit             volume_series.volume_unit NOT NULL,
    granularity             volume_series.time_granularity NOT NULL,

    -- Delivery window
    delivery_start          TIMESTAMPTZ NOT NULL,
    delivery_end            TIMESTAMPTZ NOT NULL,
    delivery_timezone       VARCHAR(64) NOT NULL,

    -- Asset metadata
    rated_capacity_mw       NUMERIC(15, 6),
    metering_point_id       VARCHAR(128),

    -- Lifecycle
    quality_state           volume_series.quality_state NOT NULL,

    -- Bi-temporal audit
    transaction_time        TIMESTAMPTZ NOT NULL,       -- when system recorded this version
    received_at             TIMESTAMPTZ NOT NULL,       -- when meter data arrived
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_mas_delivery_window
        CHECK (delivery_end > delivery_start),
    CONSTRAINT chk_mas_quality_state
        CHECK (quality_state IN ('PROVISIONAL', 'VALIDATED', 'ESTIMATED'))
);

-- One active version per series_key
CREATE UNIQUE INDEX uq_mas_series_key_version
    ON volume_series.metered_actual_volume_series (tenant_id, series_key, version_id);

ALTER TABLE volume_series.metered_actual_volume_series ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.metered_actual_volume_series
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.10 metered_actual_interval — Partitioned by interval_start

```sql
CREATE TABLE volume_series.metered_actual_interval (
    id                  BIGINT NOT NULL
                            DEFAULT nextval('volume_series.metered_interval_seq'),
    interval_uuid       UUID NOT NULL,
    series_id           BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,

    interval_start      TIMESTAMPTZ NOT NULL,
    interval_end        TIMESTAMPTZ NOT NULL,

    volume              NUMERIC(15, 6) NOT NULL,
    energy              NUMERIC(18, 6) NOT NULL,

    -- Meter-specific quality per interval
    quality_state       volume_series.quality_state NOT NULL,

    -- Forward-link versioning (append-only corrections)
    version             INTEGER NOT NULL DEFAULT 1,
    supersedes_id       BIGINT,

    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_mai_window
        CHECK (interval_end > interval_start),
    CONSTRAINT chk_mai_quality_state
        CHECK (quality_state IN ('PROVISIONAL', 'VALIDATED', 'ESTIMATED')),
    CONSTRAINT chk_mai_supersedes_versioning
        CHECK (
            (supersedes_id IS NULL AND version = 1)
            OR (supersedes_id IS NOT NULL AND version > 1)
        ),

    PRIMARY KEY (id, interval_start)
) PARTITION BY RANGE (interval_start);

ALTER TABLE volume_series.metered_actual_interval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.metered_actual_interval
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.11 compaction_view + compacted_interval

```sql
CREATE TABLE volume_series.compaction_view (
    id                  BIGINT PRIMARY KEY
                            DEFAULT nextval('volume_series.compaction_view_seq'),
    view_uuid           UUID NOT NULL UNIQUE,
    tenant_id           UUID NOT NULL,
    source_series_id    BIGINT NOT NULL,
    source_layer        volume_series.volume_layer NOT NULL,
    target_granularity  volume_series.time_granularity NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_cv_source_target UNIQUE (source_series_id, source_layer, target_granularity)
);

CREATE TABLE volume_series.compacted_interval (
    id                      BIGINT PRIMARY KEY
                                DEFAULT nextval('volume_series.compacted_interval_seq'),
    view_id                 BIGINT NOT NULL,
    tenant_id               UUID NOT NULL,
    interval_start          TIMESTAMPTZ NOT NULL,
    interval_end            TIMESTAMPTZ NOT NULL,
    volume                  NUMERIC(15, 6) NOT NULL,   -- energy-weighted avg for MW; sum for MWh
    energy                  NUMERIC(18, 6) NOT NULL,   -- sum of source energies
    source_interval_count   INTEGER NOT NULL,

    CONSTRAINT fk_ci_view FOREIGN KEY (view_id)
        REFERENCES volume_series.compaction_view(id) ON DELETE CASCADE,
    CONSTRAINT chk_ci_window CHECK (interval_end > interval_start)
);

ALTER TABLE volume_series.compaction_view ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.compaction_view
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE volume_series.compacted_interval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.compacted_interval
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.12 materialization_chunk_status

```sql
CREATE TABLE volume_series.materialization_chunk_status (
    id                  BIGINT PRIMARY KEY
                            DEFAULT nextval('volume_series.chunk_status_seq'),
    series_id           BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,
    chunk_month         DATE NOT NULL,              -- first day of month
    status              volume_series.chunk_status NOT NULL,
    expected_intervals  INTEGER NOT NULL,
    actual_intervals    INTEGER NOT NULL DEFAULT 0,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_chunk_series FOREIGN KEY (series_id)
        REFERENCES volume_series.volume_series(id) ON DELETE CASCADE,
    CONSTRAINT uq_chunk_series_month UNIQUE (series_id, chunk_month)
);

ALTER TABLE volume_series.materialization_chunk_status ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.materialization_chunk_status
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.13 trade_interval_cache (S6b — Optional)

```sql
CREATE TABLE volume_series.trade_interval_cache (
    id                  BIGINT PRIMARY KEY
                            DEFAULT nextval('volume_series.trade_interval_cache_seq'),
    tenant_id           UUID NOT NULL,
    trade_leg_id        VARCHAR(64) NOT NULL,
    interval_start      TIMESTAMPTZ NOT NULL,
    interval_end        TIMESTAMPTZ NOT NULL,

    -- Pre-multiplied resolved values
    resolved_qty        NUMERIC(15, 6) NOT NULL,    -- volume × multiplier (D-12: commodity-neutral name)
    resolved_energy     NUMERIC(18, 6) NOT NULL,    -- energy × multiplier (D-12: commodity-neutral name)
    multiplier          NUMERIC(8, 6) NOT NULL,     -- snapshot of VolumeReference.multiplier
    series_key          VARCHAR(128) NOT NULL,       -- which volume_series was used
    version_hash        VARCHAR(64) NOT NULL,        -- detects staleness

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_tic_window CHECK (interval_end > interval_start)
);

-- S6b is NOT source of truth. Entirely rebuildable from:
--   volume_reference → volume_series → volume_interval × multiplier
-- Rebuild triggers: VolumeSuperseded, VolumeReference changed, trade amended.

ALTER TABLE volume_series.trade_interval_cache ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON volume_series.trade_interval_cache
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

### 5.14 Immutability Triggers

```sql
-- PROFILE series immutability: no updates to volume/energy fields
CREATE OR REPLACE FUNCTION volume_series.prevent_profile_update()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM volume_series.volume_series vs
        WHERE vs.id = OLD.series_id AND vs.series_type = 'PROFILE'
    ) THEN
        IF OLD.volume IS DISTINCT FROM NEW.volume
           OR OLD.energy IS DISTINCT FROM NEW.energy
           OR OLD.status IS DISTINCT FROM NEW.status
        THEN
            RAISE EXCEPTION 'PROFILE intervals are immutable (interval id=%)', OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Applied per partition (pg_partman creates partitions; trigger must be added via event trigger)

-- METERED_ACTUAL append-only: corrections must insert new rows with supersedes_id
CREATE OR REPLACE FUNCTION volume_series.prevent_metered_actual_update()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.volume IS DISTINCT FROM NEW.volume
           OR OLD.energy IS DISTINCT FROM NEW.energy
           OR OLD.version IS DISTINCT FROM NEW.version
           OR OLD.supersedes_id IS DISTINCT FROM NEW.supersedes_id
        THEN
            RAISE EXCEPTION
                'METERED_ACTUAL intervals are append-only; corrections must create a new row with supersedes_id set'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Delete protection for PROFILE intervals (only retention purge allowed)
CREATE OR REPLACE FUNCTION volume_series.prevent_profile_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM volume_series.volume_series vs
        WHERE vs.id = OLD.series_id AND vs.series_type = 'PROFILE'
    ) THEN
        IF current_setting('app.retention_purge_active', true) IS DISTINCT FROM 'true' THEN
            RAISE EXCEPTION 'PROFILE intervals can only be deleted by the retention purge job'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
```

### 5.15 Bi-Temporal Audit Table

```sql
CREATE TABLE volume_audit.series_history (
    id                  BIGSERIAL PRIMARY KEY,
    series_id           BIGINT NOT NULL,
    tenant_id           UUID NOT NULL,
    series_key          VARCHAR(128) NOT NULL,
    series_type         volume_series.series_type NOT NULL,
    trade_id            VARCHAR(64),
    trade_version       INTEGER,
    operation           VARCHAR(16) NOT NULL,       -- 'INSERT', 'UPDATE', 'SUPERSEDE'
    transaction_time    TIMESTAMPTZ NOT NULL,
    valid_time          TIMESTAMPTZ,
    series_state        JSONB NOT NULL,             -- snapshot at this point
    actor               VARCHAR(128),

    CONSTRAINT chk_sh_operation CHECK (
        operation IN ('INSERT', 'UPDATE', 'SUPERSEDE')
    )
);
```

**What is NOT audited:**
- PROFILE intervals — immutable; the original row IS the audit record
- METERED_ACTUAL corrections — the forward-link version chain IS the audit trail
- Compacted intervals — derived, rebuildable

---

## 6. Partitioning Strategy

### 6.1 pg_partman Configuration

Aurora PostgreSQL 16 does not support TimescaleDB. All time-based partitioning uses pg_partman with native RANGE partitioning on `interval_start`.

```sql
-- Install pg_partman
CREATE EXTENSION IF NOT EXISTS pg_partman;

-- volume_interval: monthly partitions
SELECT partman.create_parent(
    p_parent_table   := 'volume_series.volume_interval',
    p_control        := 'interval_start',
    p_type           := 'native',
    p_interval       := '1 month',
    p_premake        := 6,                     -- pre-create 6 months ahead
    p_template_table := 'volume_series.volume_interval_template'
);

-- metered_actual_interval: monthly partitions
SELECT partman.create_parent(
    p_parent_table   := 'volume_series.metered_actual_interval',
    p_control        := 'interval_start',
    p_type           := 'native',
    p_interval       := '1 month',
    p_premake        := 6,
    p_template_table := 'volume_series.metered_actual_interval_template'
);

-- Template tables carry indexes and triggers for new partitions
```

### 6.2 Partition Naming Convention

pg_partman creates child tables with the naming pattern:
```
volume_series.volume_interval_p2026_07
volume_series.volume_interval_p2026_08
volume_series.metered_actual_interval_p2026_07
```

### 6.3 pg_cron Maintenance

```sql
-- Install pg_cron
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Run pg_partman maintenance daily at 01:00 UTC
-- Creates new partitions, handles retention drops
SELECT cron.schedule(
    'partman-maintenance',
    '0 1 * * *',
    $$CALL partman.run_maintenance_proc()$$
);
```

### 6.4 Why NOT TimescaleDB

TimescaleDB is **not available** on Aurora PostgreSQL. Aurora uses a custom storage engine incompatible with TimescaleDB's chunk management. pg_partman provides equivalent functionality for our use case:

| Capability | TimescaleDB (V1.3) | pg_partman (V2.0) |
|---|---|---|
| Auto-partition by time | `create_hypertable()` | `create_parent()` |
| Pre-create future partitions | `chunk_time_interval` | `p_premake` |
| Retention drop | `drop_chunks()` | `run_maintenance_proc()` with `p_retention` |
| Compression | Native compression policy | Aurora TOAST (see §8) |
| Continuous aggregates | Native | Materialized views + pg_cron |

---

## 7. Index Strategy

### 7.1 volume_series Indexes

```sql
-- Version lookup: find specific version of a series
CREATE INDEX idx_vs_series_key_version
    ON volume_series.volume_series (tenant_id, series_key, version_id);

-- Asset-based lookup: all FORECAST series for an asset
CREATE INDEX idx_vs_asset
    ON volume_series.volume_series (asset_id)
    WHERE asset_id IS NOT NULL;

-- Trade-leg lookup: PROFILE series for a trade
CREATE INDEX idx_vs_trade_leg
    ON volume_series.volume_series (trade_leg_id)
    WHERE trade_leg_id IS NOT NULL;

-- Tenant + trade: find all series for a trade
CREATE INDEX idx_vs_tenant_trade
    ON volume_series.volume_series (tenant_id, trade_id);

-- Pending materialization: chunk processor polling
CREATE INDEX idx_vs_tenant_status
    ON volume_series.volume_series (tenant_id, materialization_status)
    WHERE materialization_status IN ('PENDING', 'PARTIAL', 'FAILED');

-- Delivery window: range queries
CREATE INDEX idx_vs_delivery_window
    ON volume_series.volume_series (tenant_id, delivery_start, delivery_end);
```

### 7.2 volume_interval Indexes

```sql
-- Primary access: intervals for a series in time order
CREATE INDEX idx_vi_series_time
    ON volume_series.volume_interval (series_id, interval_start);

-- Tenant + time: cross-series time range queries
CREATE INDEX idx_vi_tenant_time
    ON volume_series.volume_interval (tenant_id, interval_start DESC);

-- Chunk month: rolling-horizon chunk lookups
CREATE INDEX idx_vi_chunk_month
    ON volume_series.volume_interval (series_id, chunk_month)
    WHERE chunk_month IS NOT NULL;

-- Forward-link supersession anti-join
CREATE INDEX idx_vi_supersedes
    ON volume_series.volume_interval (supersedes_id)
    WHERE supersedes_id IS NOT NULL;

-- Logical latest: current-version lookup
CREATE INDEX idx_vi_logical_latest
    ON volume_series.volume_interval (series_id, interval_start, version DESC);
```

### 7.3 volume_reference Indexes

```sql
-- Trade-leg lookup: volume series for a trade
CREATE INDEX idx_vr_trade_leg
    ON volume_series.volume_reference (trade_leg_id);

-- Asset fan-out: all trades referencing an asset
CREATE INDEX idx_vr_asset
    ON volume_series.volume_reference (asset_id)
    WHERE asset_id IS NOT NULL;

-- Series key lookup: which references use a given volume series
CREATE INDEX idx_vr_volume_series_key
    ON volume_series.volume_reference (volume_series_key);

-- Metered series lookup: which references use a given metered series
CREATE INDEX idx_vr_metered_series_key
    ON volume_series.volume_reference (metered_series_key)
    WHERE metered_series_key IS NOT NULL;

-- Effective window: active references at a point in time
CREATE INDEX idx_vr_effective
    ON volume_series.volume_reference (trade_leg_id, effective_from, effective_to);
```

### 7.4 metered_actual Indexes

```sql
-- Series + time: intervals for a metered series
CREATE INDEX idx_mai_series_time
    ON volume_series.metered_actual_interval (series_id, interval_start);

-- Version lookup
CREATE UNIQUE INDEX uq_mas_series_key_active
    ON volume_series.metered_actual_volume_series (tenant_id, series_key)
    WHERE quality_state IN ('PROVISIONAL', 'VALIDATED');

-- Forward-link supersession
CREATE INDEX idx_mai_supersedes
    ON volume_series.metered_actual_interval (supersedes_id)
    WHERE supersedes_id IS NOT NULL;
```

### 7.5 Supporting Table Indexes

```sql
-- Chunk status: pending work
CREATE INDEX idx_mcs_pending
    ON volume_series.materialization_chunk_status (tenant_id, status, chunk_month)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

-- Chunk status: DLQ monitoring
CREATE INDEX idx_mcs_dlq
    ON volume_series.materialization_chunk_status (tenant_id, retry_count, updated_at)
    WHERE status = 'DLQ';

-- Audit: by series + time
CREATE INDEX idx_sh_series_time
    ON volume_audit.series_history (series_id, transaction_time DESC);

CREATE INDEX idx_sh_tenant_trade
    ON volume_audit.series_history (tenant_id, trade_id, transaction_time DESC);

-- Trade interval cache (S6b)
CREATE INDEX idx_tic_trade_leg_time
    ON volume_series.trade_interval_cache (trade_leg_id, interval_start);

CREATE INDEX idx_tic_tenant_time
    ON volume_series.trade_interval_cache (tenant_id, interval_start);
```

### 7.6 Index Design Principles

1. **Tenant-leading:** Every index on a multi-tenant table starts with `tenant_id` (or the table is small enough that full scans are acceptable)
2. **Partial indexes:** Use `WHERE` clauses to keep index size proportional to relevant subset (e.g., only PENDING/FAILED materialization statuses)
3. **Covering indexes:** Where feasible, include queried columns to enable index-only scans
4. **Planner statistics:** Set elevated statistics targets on high-cardinality partition keys:

```sql
ALTER TABLE volume_series.volume_interval
    ALTER COLUMN tenant_id SET STATISTICS 1000;
ALTER TABLE volume_series.volume_interval
    ALTER COLUMN interval_start SET STATISTICS 1000;
ANALYZE volume_series.volume_interval;
```

---

## 8. Storage & Compression

### 8.1 Aurora TOAST Compression

Aurora PostgreSQL uses TOAST (The Oversized-Attribute Storage Technique) for automatic compression of large column values. Unlike TimescaleDB's native chunk compression, TOAST operates at the column level and is transparent.

For volume interval data (predominantly `NUMERIC` values), TOAST provides modest compression. The primary storage optimization comes from the **unified model itself** — eliminating per-trade forecast and metered copies saves ~200M+ rows compared to V1.3's approach.

### 8.2 Storage Sizing over 7-Year Horizon

| Period | volume_interval | metered_actual_interval | Other Tables | Total |
|---|---|---|---|---|
| Year 1 | ~90M rows / ~40 GB | ~55M rows / ~20 GB | ~5 GB | ~65 GB |
| Year 3 | ~270M rows / ~120 GB | ~165M rows / ~60 GB | ~10 GB | ~190 GB |
| Year 7 (steady state w/ retention) | ~630M rows / ~280 GB | ~385M rows / ~140 GB | ~20 GB | ~440 GB |

**Row size estimates:**
- `volume_interval`: ~450 bytes/row (including index overhead)
- `metered_actual_interval`: ~380 bytes/row
- `volume_series` header: ~600 bytes/row
- `volume_reference`: ~400 bytes/row

### 8.3 Cost Model (Storage)

Aurora storage: $0.10/GB-month (I/O-Optimized, data only). Note: §3.2 uses $0.225/GB-month which includes backup storage ($0.021/GB-month) and Aurora storage overhead.

| Year | Storage (GB) | Monthly Cost |
|---|---|---|
| 1 | ~65 | ~$7 |
| 3 | ~190 | ~$19 |
| 7 | ~440 | ~$44 |

Storage cost is negligible relative to compute (~$3,600/month for writer + 2 readers).

---

## 9. Retention Strategy

### 9.1 Policy

| Data Class | Retention | Regulatory Basis |
|---|---|---|
| Volume intervals (all types) | 7 years post-settlement finalization | REMIT (5yr), MiFID II (5–7yr), EMIR (5yr) |
| Metered actual intervals | 7 years post-settlement finalization | Same |
| Volume series headers | 7 years (same as intervals) | Same |
| Volume references | Lifetime of associated trade + 7 years | Same |
| Audit records | 7 years | Same |
| Trade interval cache (S6b) | Hot window only (2 months); no long-term retention | Rebuildable; not regulatory data |

### 9.2 Partition Drop via pg_partman

```sql
-- Configure retention on volume_interval
UPDATE partman.part_config
SET retention          = '90 months',   -- ~7.5 years (7yr + buffer for settlement clock gap)
    retention_keep_table = false         -- DROP partition, don't just detach
WHERE parent_table = 'volume_series.volume_interval';

-- Same for metered_actual_interval
UPDATE partman.part_config
SET retention          = '90 months',
    retention_keep_table = false
WHERE parent_table = 'volume_series.metered_actual_interval';
```

The daily pg_cron job (`partman.run_maintenance_proc()`) handles partition drops automatically.

### 9.3 Pre-Retention Audit

Before dropping partitions, a safety check runs at 02:00 UTC (one hour before the 03:00 UTC maintenance window):

```sql
CREATE TABLE volume_series.retention_exceptions (
    id              BIGSERIAL PRIMARY KEY,
    partition_name  TEXT NOT NULL,
    range_start     TIMESTAMPTZ NOT NULL,
    range_end       TIMESTAMPTZ NOT NULL,
    unsettled_count INTEGER NOT NULL,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT
);
```

If any intervals in a partition-to-be-dropped have no `settlement_finalized_at`, the partition is excluded from the drop and an alert is raised. This prevents accidental deletion of unsettled data.

### 9.4 Cold Tier Archival

Before partitions age out of the WARM tier (12–14 months post-delivery), they are archived to S3/Glacier:

1. `pg_dump` of the partition to Parquet format
2. Upload to S3 with lifecycle policy for Glacier transition at 24 months
3. Partition remains in Aurora until the 7-year drop

Archived data is queryable via Athena for regulatory reporting. Latency: minutes (acceptable for regulatory/audit queries).

### 9.5 GDPR Considerations

Volume interval data is keyed by `trade_id` and `tenant_id`, not by natural person. For tenant deactivation:

1. Tenant status set to `DEACTIVATED`
2. Volume interval rows survive deactivation (regulatory retention)
3. Final purge only after 7-year retention expires per partition
4. RLS policies prevent deactivated tenants from querying their own data

---

## 10. Bi-Temporal Audit

### 10.1 Scope

The `series_history` table (§5.15) captures:

| Event | `operation` | What is recorded |
|---|---|---|
| New series created | `INSERT` | Full series state as JSONB |
| Forecast supersession | `SUPERSEDE` | Previous state + new version_id |
| Trade amendment | `SUPERSEDE` | Previous PROFILE state + new trade_version |
| Quality state change | `UPDATE` | Before/after quality_state |

### 10.2 What Is NOT Audited at DB Level

| Data | Reason |
|---|---|
| PROFILE intervals | Immutable — the original row IS the audit record |
| METERED_ACTUAL interval corrections | Forward-link version chain (supersedes_id) IS the audit trail |
| FORECAST interval data | Supersession preserves all old rows (quality_state = SUPERSEDED); queryable via version_id |
| Compacted intervals | Derived; rebuildable from source intervals |
| Trade interval cache (S6b) | Ephemeral; rebuildable |

### 10.3 Audit Trigger

```sql
CREATE OR REPLACE FUNCTION volume_audit.log_series_change()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO volume_audit.series_history (
        series_id, tenant_id, series_key, series_type,
        trade_id, trade_version, operation,
        transaction_time, valid_time, series_state, actor
    ) VALUES (
        COALESCE(NEW.id, OLD.id),
        COALESCE(NEW.tenant_id, OLD.tenant_id),
        COALESCE(NEW.series_key, OLD.series_key),
        COALESCE(NEW.series_type, OLD.series_type),
        COALESCE(NEW.trade_id, OLD.trade_id),
        COALESCE(NEW.trade_version, OLD.trade_version),
        CASE
            WHEN TG_OP = 'INSERT' THEN 'INSERT'
            WHEN NEW.quality_state IN ('SUPERSEDED', 'AMENDED')
                 AND OLD.quality_state NOT IN ('SUPERSEDED', 'AMENDED')
            THEN 'SUPERSEDE'
            ELSE 'UPDATE'
        END,
        now(),
        COALESCE(NEW.valid_time, OLD.valid_time),
        to_jsonb(COALESCE(NEW, OLD)),
        current_setting('app.actor', true)
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_volume_series_audit
    AFTER INSERT OR UPDATE ON volume_series.volume_series
    FOR EACH ROW EXECUTE FUNCTION volume_audit.log_series_change();
```

---

## 11. Multitenancy Enforcement

### 11.1 Three-Layer Isolation

**Layer 1 — Application (`@TenantAware` aspect):**
Sets `app.tenant_id` session variable on every database connection:
```sql
SET app.tenant_id = 'TN_0042';
```

**Layer 2 — RLS (Row-Level Security):**
Every domain table has:
```sql
ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON <table>
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

The `true` parameter in `current_setting` returns NULL (not error) if the variable is unset, causing the policy to match zero rows — fail-closed.

**Layer 3 — Index leading key:**
Every composite index starts with `tenant_id`, ensuring partition pruning and index scans are tenant-scoped.

### 11.2 Superuser Bypass

Administrative operations (retention purge, cross-tenant analytics) use a dedicated role that bypasses RLS:
```sql
ALTER ROLE volume_admin BYPASSRLS;
```

This role is used only by pg_cron jobs and operational scripts, never by application code.

### 11.3 Large Tenant Promotion

Tenants exceeding sizing thresholds (e.g., >50 assets, >500 active trades) are promotable to a dedicated Aurora cluster. The schema is identical — only the connection string changes. No application code changes required.

---

## 12. Cache Strategy (Redis)

### 12.1 Scope

Redis caches the **near-term** window only: current month (M) + M+1 + M+2. This aligns with the HOT tier and the trader dashboard's primary query pattern.

### 12.2 Cache Key Structure

```
vol:{tenant_id}:{series_key}:{interval_start_iso}
```

Example: `vol:TN_0042:FCST-WP-NORDSEE:2026-08-15T14:00:00Z`

### 12.3 Sizing

| Dimension | Value |
|---|---|
| Tenants | 200 |
| Series per tenant (avg) | ~50 (8 assets × ~3 forecast versions + profiles) |
| Intervals per day | 96 (15-min) |
| Near-term window | 90 days (M+M+1+M+2) |
| **Total keys** | **~86M** (worst case) |
| Bytes per key+value | ~200 |
| **Total memory** | **~17 GB** |

In practice, only CURRENT/EFFECTIVE series are cached, reducing to ~26M keys / ~5 GB. Fits in `cache.r7g.large` (13 GB usable) with headroom.

### 12.4 TTL and Eviction

- **TTL:** 24 hours
- **Eviction policy:** `allkeys-lru`
- **Active eviction:** pg_cron job evicts keys outside the rolling near-term window

### 12.5 Invalidation

Cache invalidation is **event-driven**, triggered after DB commit:

1. `VolumeSuperseded` event → invalidate all keys for the affected `series_key` within the `affected_range`
2. `VolumeReference` changed → invalidate all keys for the old and new `volume_series_key`
3. Trade amendment → invalidate all keys for the trade's PROFILE `series_key`

### 12.6 Read-Through Pattern

Redis is NOT an independent computation layer. Pattern:

1. Read from Redis by key
2. On miss → query PostgreSQL (reader endpoint) → populate Redis → return
3. On hit → return cached value

Redis outage degrades latency, not correctness. Strong consistency reads bypass Redis via `read_consistent=true` flag → routes to writer endpoint.

### 12.7 Redis Configuration

| Setting | Value | Rationale |
|---|---|---|
| Instance type | `cache.r7g.large` | 13 GB usable; sufficient for ~26M keys |
| Cluster mode | Disabled (single shard) | Data fits in one node |
| Multi-AZ | Yes | Automatic failover |
| Engine | Redis 7 | Pipeline + MULTI-EXEC support |
| Backup | Daily snapshot | Recovery < 5 min |

---

## 13. Event Integration

### 13.1 Inbound Events

| Event | Source | Action |
|---|---|---|
| `trade.captured` | Trade service (via Kafka) | Create `VolumeReference` + PROFILE `VolumeSeries` with intervals |
| `trade.amended` | Trade service | Create new PROFILE version; mark old as AMENDED; update VolumeReference if needed |
| `trade.cancelled` | Trade service | Mark PROFILE as AMENDED (terminal); soft-delete VolumeReference |
| `forecast.published` | Forecast service | Create new FORECAST `VolumeSeries` version; mark old as SUPERSEDED |
| `meter.received` | Metering service | Create/append `MeteredActualVolumeSeries` + intervals |

### 13.2 Outbound Events

| Event | Trigger | Payload (per V3.0 §8) | Consumers |
|---|---|---|---|
| `VolumePublished` | New series version created | `series_key`, `layer`, `series_type`, `version_id`, `delivery_range`, `granularity`, `quality_state`, `scope` (FULL/PARTIAL), `event_time` | Position/valuation, slot cache, S6b |
| `VolumeSuperseded` | Existing volume replaced | `series_key`, `layer`, `series_type`, `affected_range`, `old_version_id`, `new_version_id`, `quality_state`, `event_time` | Revaluation trigger, dependency index (S8), S6b rebuild |
| `VolumeChunkMaterialized` | Chunk materialization completes | `series_key`, `chunk_month`, `chunk_status`, `materialized_count`, `event_time` | Monitoring, materialization dashboard |

**VolumeSuperseded** is the primary revaluation trigger. Its `event_time` becomes `known_from` on the resulting valuation-cell versions.

### 13.3 Idempotent Consumer Design

```java
public void handleTradeCaptured(TradeCapturedEvent event) {
    if (volumeReferenceRepository.existsByTradeIdAndTradeVersion(
            event.getTradeId(), event.getTradeVersion())) {
        log.info("Idempotent skip: trade={} version={}",
                 event.getTradeId(), event.getTradeVersion());
        return;
    }
    // ... create VolumeReference + VolumeSeries ...
}
```

Natural idempotency key: `(trade_id, trade_version)`. At-least-once delivery guaranteed by Kafka consumer with manual commit + InFlightOffsetTracker.

### 13.4 Outbox Pattern

```sql
CREATE TABLE trade.outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,       -- e.g. 'VolumeReference'
    aggregate_id    VARCHAR(64) NOT NULL,       -- e.g. series_key
    event_type      VARCHAR(64) NOT NULL,       -- e.g. 'VolumePublished'
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished
    ON trade.outbox (created_at)
    WHERE published_at IS NULL;
```

Relay process polls the outbox and publishes to Kafka with at-least-once delivery, marking `published_at` only after broker ACK. Kafka topic retention: 7 days (5-day consumer outage recoverable by replay).

### 13.5 Saga Pattern

```sql
CREATE TABLE trade.saga_state (
    saga_id         UUID PRIMARY KEY,
    trade_id        VARCHAR(64) NOT NULL,
    saga_type       VARCHAR(64) NOT NULL,       -- e.g. 'TRADE_CAPTURE'
    state           VARCHAR(32) NOT NULL,       -- STARTED, VOLUME_CREATED, FAILED_REQUIRES_REVIEW
    started_at      TIMESTAMPTZ NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL,
    error_context   JSONB
);
```

Sagas stuck in `STARTED` for >30 minutes are surfaced to operations via monitoring.

---

## 14. SLA Targets

### 14.1 Write SLAs

| Operation | Scope | p95 | p99 |
|---|---|---|---|
| Single interval insert | 1 row | 5 ms | 15 ms |
| Batch insert (DA day, 96 rows) | 96 rows | 30 ms | 80 ms |
| Batch insert (monthly chunk, ~2,976 rows) | 2,976 rows | 300 ms | 800 ms |
| VolumeSeries create/supersede | 1 row + cascade | 15 ms | 40 ms |
| VolumeReference create | 1 row | 8 ms | 25 ms |
| Chunk status update | 1 row | 5 ms | 15 ms |
| MeteredActual append | 1 row | 8 ms | 25 ms |

### 14.2 Read SLAs

| Operation | Scope | p95 | p99 |
|---|---|---|---|
| Series by ID | 1 row | 3 ms | 10 ms |
| PROFILE intervals for one month | ~30–100 rows | 20 ms | 50 ms |
| FORECAST intervals for one day | 96 rows | 5 ms | 20 ms |
| VolumeReference for trade-leg | 1–3 rows | 3 ms | 10 ms |
| Intervals for 1 tenant / 1 month | ~10K rows | 50 ms | 150 ms |
| Cross-tenant settlement run | ~3M rows | 1 sec | 3 sec |
| Redis hit (cached interval) | 1 key | 1 ms | 5 ms |
| Redis miss → DB read | ~96 rows | 10 ms | 30 ms |
| Regulatory report (1 month) | ~3M rows | 5 sec | 15 sec |

### 14.3 Position Resolution SLAs

| Operation | Path | p95 | p99 |
|---|---|---|---|
| `queryVolumeForTradeLeg` (single interval) | volume_reference → volume_series → volume_interval | 8 ms | 25 ms |
| `queryVolumeForTradeLeg` (full day, 96 intervals) | Same, batch | 15 ms | 40 ms |
| Portfolio position (1 zone, 1 day) | 3-join: position_ledger → volume_reference → volume_interval × multiplier | 30 ms | 100 ms |
| S6b cache lookup (1 trade, 1 day) | trade_interval_cache direct | 5 ms | 15 ms |

### 14.4 Critical Path Budget

Kafka consume → domain logic → DB write → commit → cache invalidate → Kafka produce:

| Step | Budget |
|---|---|
| Kafka consume | 5 ms |
| Domain logic | 30 ms |
| DB write | 50 ms |
| Commit | 10 ms |
| Cache invalidation (Redis) | 5 ms |
| Kafka produce | 5 ms |
| Buffer | 145 ms |
| **Total p95** | **250 ms** |

### 14.5 Replication Lag

| Metric | Threshold | Action |
|---|---|---|
| Normal | < 200 ms p95 | — |
| Elevated | < 1 sec p99 | — |
| Alert | > 2 sec sustained for 30 sec | Page on-call |
| Fallback | > 500 ms | Route reads to writer endpoint |

---

## 15. Backup & DR

### 15.1 Aurora Automated Backups

| Feature | Configuration |
|---|---|
| Continuous backups | Enabled (Aurora default) |
| Backup retention | 35 days |
| Point-in-time restore | To any second within retention window |
| Cross-region replication | Optional (for DR); not enabled by default |

### 15.2 RPO / RTO

| Metric | Target | How |
|---|---|---|
| RPO (Recovery Point Objective) | < 5 seconds | Aurora continuous backup + transaction log streaming |
| RTO (Recovery Time Objective) | < 10 minutes | Aurora automated failover (writer → standby) |
| Full-cluster RTO | < 30 minutes | Point-in-time restore to new cluster |

### 15.3 Manual Snapshots

Monthly manual snapshots retained for 1 year. Used for:
- Cross-account DR testing
- Environment cloning (staging from production)
- Long-term archival beyond automated backup retention

### 15.4 DR Runbook (Summary)

1. **Writer failure:** Aurora automatic failover to standby (< 30s)
2. **AZ failure:** Multi-AZ deployment; automatic failover; reader in second AZ promoted
3. **Region failure:** Cross-region replica promoted (if configured); RPO = replication lag (~seconds)
4. **Logical corruption:** Point-in-time restore to pre-corruption timestamp; replay events from Kafka (7-day retention)

---

## 16. Migration Strategy

### 16.1 Flyway Configuration

Migrations in `src/main/resources/db/migration`.

Versioning convention: `V{YYYYMMDD}{HHMM}__{description}.sql` (timestamp-based to avoid merge conflicts).

Online schema change: **expand/contract** pattern:
1. Add nullable column
2. Backfill in batches (background job, not migration)
3. Add NOT NULL constraint after backfill
4. Remove old column in later release

For Aurora: `CREATE INDEX CONCURRENTLY` is supported. No TimescaleDB-specific caveats (no chunk decompression required).

### 16.2 V1.3 → V2.0 Migration Path

This migration is a **structural rewrite**. Data must be transformed, not simply renamed.

#### Phase 1: Schema Migration

```sql
-- V2.0 migration: create new tables alongside V1.3 tables
-- File: V20260801_0001__create_v2_schema.sql

-- 1. Create new enum types (§5.2)
-- 2. Create volume_reference table (§5.5)
-- 3. Create new volume_series table with V3.0 fields (§5.3)
-- 4. Create metered_actual_volume_series (§5.9)
-- 5. Create metered_actual_interval (§5.10)
-- 6. Update volume_interval to remove wide-table scalars
-- 7. Create trade_interval_cache (§5.13)
```

#### Phase 2: Data Migration

```sql
-- File: V20260801_0002__migrate_v1_data.sql

-- 1. Populate volume_reference from existing volume_series (trade_leg_id + asset mapping)
--    multiplier = 1.0 for all existing trades (V1.3 had no shared-asset model)
-- 2. Split V1.3 volume_series into:
--    a. volume_series (PROFILE type) for CONTRACTUAL bucket_type rows
--    b. volume_series (FORECAST type) for PLAN bucket_type rows (if any)
-- 3. Move ACTUAL bucket_type intervals to metered_actual_interval
-- 4. Generate series_key from existing trade_id/trade_leg_id
-- 5. Map quality_state: CONTRACTUAL → EFFECTIVE, live PLAN → CURRENT
-- 6. Drop cascade_tier, bucket_type, wide-table scalar columns
```

#### Phase 3: Validation

```sql
-- File: V20260801_0003__validate_migration.sql

-- 1. Row count reconciliation: old vs new tables
-- 2. Spot-check volume/energy sums per trade_id
-- 3. Verify all CONTRACTUAL intervals have corresponding PROFILE volume_series
-- 4. Verify all volume_references have valid volume_series_key
-- 5. Verify no orphaned intervals (intervals without parent series)
```

#### Phase 4: Cleanup

```sql
-- File: V20260815_0001__drop_v1_artifacts.sql (2 weeks after Phase 3)

-- 1. Drop old enum types (bucket_type, cascade_tier)
-- 2. Drop old indexes referencing removed columns
-- 3. Drop migration-specific temporary tables
```

---

## 17. Operational Notes

### 17.1 Hibernate Batch Inserts

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 1000
        order_inserts: true
        order_updates: true
        jdbc.batch_versioned_data: true
```

Without `order_inserts=true`, Hibernate flushes per entity type — a chunk of 2,976 intervals becomes many small JDBC roundtrips. This single configuration is worth ~5× throughput on the chunk processor.

### 17.2 Batch Insert Pattern

```java
@Transactional
public void persistChunk(VolumeSeries series, List<VolumeInterval> intervals) {
    entityManager.persist(series);
    for (int i = 0; i < intervals.size(); i++) {
        entityManager.persist(intervals.get(i));
        if (i % 1000 == 0) {
            entityManager.flush();
            entityManager.clear();
        }
    }
}
```

Flush every 1,000 rows to prevent first-level cache from growing unbounded.

### 17.3 Timezone Handling at Database Boundary

PostgreSQL stores `TIMESTAMPTZ` as UTC internally. Application stores delivery times in market-local timezone (e.g., `Europe/Berlin`).

**Risk 1:** JDBC driver respects server `timezone` setting unless overridden. Connection URL must include:
```
?stringtype=unspecified&assumeMinServerVersion=16
```
And each connection must execute `SET TIME ZONE 'UTC'` at acquisition (HikariCP `connectionInitSql`).

**Risk 2:** DST-crossing intervals stored as `TIMESTAMPTZ` round-trip correctly. `DATE` intervals lose timezone info — intentional for `DAILY` granularity (calendar concept), but never convert `DATE` → `TIMESTAMPTZ` without explicit zone.

**DST rule (D-10):** MarketCalendar service is sole authority for interval generation. No timestamp arithmetic (`start + n×15min`) anywhere. EU day = 96 QH normal, 100 fall-back, 92 spring-forward. Duplicated fall-back hour explicitly disambiguated.

### 17.4 No FK to Trade (Absolute Rule)

References to `trade_id` and `trade_leg_id` are `VARCHAR(64)` without enforced foreign keys. Intentional: async write order (trade captured event may arrive before/after volume creation) can violate referential integrity during normal operation.

Orphan reaper job periodically checks for volume series where `trade_id` does not exist in the trade service; alerts for human review. Never auto-deletes — regulatory data.

### 17.5 Aurora Parameter Group

```
shared_preload_libraries = 'pg_partman_bgw,pg_cron'
pg_partman_bgw.dbname = 'volume_db'
pg_partman_bgw.interval = 3600      -- check every hour
cron.database_name = 'volume_db'
max_connections = 200
work_mem = '64MB'                   -- elevated for interval queries
maintenance_work_mem = '512MB'
effective_cache_size = '48GB'       -- ~75% of writer RAM
random_page_cost = 1.1              -- Aurora storage is SSD-like
```

For analytical queries (regulatory reports), set per-session:
```sql
SET work_mem = '256MB';
```
Not as global config (memory pressure during write bursts).

### 17.6 INTEGER Overflow Check

`materialized_interval_count` as `INTEGER` is correct: a 10-year PPA at 15-min granularity = ~350K intervals per series, far below INTEGER max of ~2.1B. Flag if a single series could accumulate more (e.g., 5-min granularity over 30+ years).

### 17.7 Serializer Mismatch Risk

Kafka consumer/producer `ByteArraySerializer` vs `JsonDeserializer` mismatch fails silently or produces corrupt records. Resolve before go-live. Recommended: Avro or Protobuf with a schema registry. JSON without a schema registry is a known failure mode.

### 17.8 Tenant Churn Lifecycle

Deactivated tenants must retain data for 7 years post-settlement. Tenant table requires `status` column: `ACTIVE` → `DEACTIVATED` → `PURGED`. Volume interval rows survive deactivation. Final purge only after retention expires.

### 17.9 Observability Metrics

| Metric | Type | Description |
|---|---|---|
| `volume_interval_inserts_total{series_type}` | Counter | Inserts by series type |
| `volume_interval_query_duration_seconds{operation}` | Histogram | Query latency |
| `chunk_processing_duration_seconds` | Histogram | Chunk materialization time |
| `chunk_status_total{status}` | Gauge | Current count per status |
| `db_connection_pool_active{pool}` | Gauge | Active connections |
| `db_connection_pool_wait_duration_seconds{pool}` | Histogram | Pool acquisition wait |
| `redis_cache_hit_ratio` | Gauge | Cache effectiveness |
| `volume_reference_count_total` | Gauge | Total volume references |
| `retention_partitions_dropped_total` | Counter | Partitions purged |

**Critical alerts:**

| Condition | Severity | Action |
|---|---|---|
| Chunk DLQ > 0 for > 5 minutes | P1 | Page on-call |
| Pool wait > 100ms p95 for > 5 minutes | P2 | Investigate connection pressure |
| Replication lag > 1sec for 30sec | P2 | Consider writer fallback |
| Disk > 80% | P2 | Review retention / add storage |
| Immutability trigger fired (any occurrence) | P1 | Investigate code path violation |

---

## 18. Scope Boundaries — What This Document Does NOT Cover

### 18.1 OUT OF SCOPE

The following subsystems are **explicitly out of scope** for this document and will have their own data architecture specifications:

| Subsystem | Why Out of Scope |
|---|---|
| **S1 — Position Ledger** | Bitemporal source-of-truth entity (D-1); separate persistence model |
| **S2 — PriceExpression** | Versioned tree with its own schema (D-2) |
| **S5a — Settlement cells** | Durable bitemporal measures; valuation-layer persistence |
| **S5b — Forward marks** | Ephemeral current-state only; market data store (D-3) |
| **S5c — EOD struck marks** | Durable immutable frozen projections (D-3) |
| **S6 — Slot Cache** | Rebuildable netted materialization; position-layer persistence |
| **S7 — Rollups** | Rebuildable aggregates; reporting-layer persistence |
| **S8 — Dependency Index** | active_leaves blast-radius optimization; owned by orchestration layer |

This document covers **S3 (VolumeSeries)** and **S6b (trade_interval_cache)** only.

### 18.2 Why V1.3's "Position as Continuous Aggregate" Was Wrong

V1.3 §18 modeled net position as a continuous aggregate derived from the BAV view. This is incorrect per the current architecture:

- **D-1:** Position Ledger is a bitemporal source-of-truth entity with its own identity and lifecycle — not a derived aggregate
- **D-8:** Zone×portfolio net position is a measure (computed/materialized in S6/S7); the trade-leg obligation is the entity. Every netted cell must be explodable to contributing ledger blocks → source trades
- Position has its own bitemporality (`valid_from/to`, `known_from/to`) independent of volume interval timestamps

### 18.3 Volume → Position Interface

The volume series module provides data to the position/valuation module via:

1. **VolumeReference** — the universal link from trade-leg to volume series + multiplier
2. **VolumePublished / VolumeSuperseded events** — trigger position revaluation
3. **Direct query** — `position_ledger → volume_reference → volume_series → volume_interval × multiplier` (the 3-join resolution path)

The position module consumes volume data; it does not own or manage it.

---

## Appendix A: Verification Checklist

| # | Check | Expected |
|---|---|---|
| 1 | No references to: TimescaleDB, hypertable, chunk_drop, continuous_aggregate, compress_chunk | Zero matches |
| 2 | No references to: bucket_type, cascade_tier, CONTRACTUAL (as bucket), PLAN (as bucket) | Zero matches |
| 3 | No references to V2.1 domain model | Zero matches |
| 4 | All V3.0 entities have DDL: volume_series, volume_interval, volume_reference, volume_formula, shaping_entry, seasonal_adjustment, metered_actual_volume_series, metered_actual_interval, compaction_view, compacted_interval, materialization_chunk_status | All present |
| 5 | Indexes match README §10.3 | `(series_id, interval_start)`, `(tenant_id, series_key, version_id)`, `(trade_leg_id)`, `(asset_id)`, `(tenant_id, delivery_start, delivery_end)`, `(trade_leg_id, interval_start)` for S6b |
| 6 | Sizing matches README §10.4 | ~56M FORECAST, ~35M PROFILE, ~28M metered, ~29M S6b, ~5K volume_reference |
| 7 | Events match V3.0 §8 | VolumePublished and VolumeSuperseded with correct payloads |
| 8 | Platform constants match context doc §12 | Aurora PG 16, pg_partman, pg_cron, Kafka 3.7 KRaft, Redis 7, Java 21, Spring Boot 3.3 |
| 9 | Quality states match FR-054 | EFFECTIVE, AMENDED, CURRENT, SUPERSEDED, PROVISIONAL, VALIDATED, ESTIMATED |
| 10 | VolumeReference DDL has all V3.0 fields | trade_leg_id, trade_id, asset_id, multiplier, volume_series_key, metered_series_key, effective_from/to |
| 11 | D-11 unified resolution documented | P8 principle + VolumeReference DDL + §18.3 interface |
| 12 | D-12 S6b documented | §5.13 DDL + §7.5 indexes + §4.2 sizing |
| 13 | Position explicitly out of scope | §18 with rationale citing D-1, D-8 |

---

## Appendix B: Document History

| Version | Date | Author | Changes |
|---|---|---|---|
| 1.0 | — | — | Initial draft (companion to V2.1 functional spec) |
| 1.1 | — | — | Added cascade tiers, compression policies |
| 1.2 | — | — | Forward-link supersession, BAV continuous aggregate |
| 1.3 | — | — | Wide-table scalars, position as continuous aggregate |
| **2.0** | **2026-07-22** | — | **Complete rewrite for V3.0 unified model. Aurora PG 16 + pg_partman. VolumeReference (D-11). S6b trade_interval_cache (D-12). Position persistence removed (D-1, D-8).** |
