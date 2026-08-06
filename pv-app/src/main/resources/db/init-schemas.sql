-- ============================================================
-- PV-APP Database Initialization Script
-- Creates schemas, sequences, tables, and indexes.
-- Runs once during PostgreSQL container first start via
-- docker-entrypoint-initdb.d. Idempotent (IF NOT EXISTS).
-- ============================================================

-- ============================================================
-- 1. SCHEMAS
-- ============================================================
CREATE SCHEMA IF NOT EXISTS market_data;
CREATE SCHEMA IF NOT EXISTS volume_series;
CREATE SCHEMA IF NOT EXISTS position;
CREATE SCHEMA IF NOT EXISTS valuation;
CREATE SCHEMA IF NOT EXISTS trade;

-- ============================================================
-- 2. SEQUENCES (allocationSize=50 matches Hibernate @SequenceGenerator)
-- ============================================================
CREATE SEQUENCE IF NOT EXISTS market_data.fixing_seq          INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS market_data.forward_curve_seq   INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS market_data.fx_rate_seq         INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS market_data.index_value_seq     INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS market_data.spread_seq          INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS market_data.vol_surface_seq     INCREMENT BY 50 START WITH 1;

CREATE SEQUENCE IF NOT EXISTS position.position_ledger_seq    INCREMENT BY 50 START WITH 1;

CREATE SEQUENCE IF NOT EXISTS valuation.settlement_cell_seq   INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS valuation.struck_mark_seq       INCREMENT BY 50 START WITH 1;

CREATE SEQUENCE IF NOT EXISTS volume_series.volume_series_seq         INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS volume_series.volume_interval_seq       INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS volume_series.trade_interval_cache_seq  INCREMENT BY 50 START WITH 1;

-- ============================================================
-- 3. TABLES
-- ============================================================

-- market_data.fixing
CREATE TABLE IF NOT EXISTS market_data.fixing (
    id              BIGINT          NOT NULL DEFAULT nextval('market_data.fixing_seq'),
    tenant_id       VARCHAR         NOT NULL,
    series          VARCHAR         NOT NULL,
    interval_start  TIMESTAMPTZ     NOT NULL,
    value           NUMERIC(18, 8)  NOT NULL,
    version_id      BIGINT          NOT NULL,
    quality_state   VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_fixing PRIMARY KEY (id)
);

-- market_data.forward_curve
CREATE TABLE IF NOT EXISTS market_data.forward_curve (
    id              BIGINT          NOT NULL DEFAULT nextval('market_data.forward_curve_seq'),
    tenant_id       VARCHAR         NOT NULL,
    series          VARCHAR         NOT NULL,
    pillar          VARCHAR(7)      NOT NULL,
    as_of_date      TIMESTAMPTZ     NOT NULL,
    value           NUMERIC(18, 8)  NOT NULL,
    version_id      BIGINT          NOT NULL,
    quality_state   VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_forward_curve PRIMARY KEY (id)
);

-- market_data.fx_rate
CREATE TABLE IF NOT EXISTS market_data.fx_rate (
    id              BIGINT          NOT NULL DEFAULT nextval('market_data.fx_rate_seq'),
    tenant_id       VARCHAR         NOT NULL,
    currency_pair   VARCHAR(7)      NOT NULL,
    reference_date  TIMESTAMPTZ     NOT NULL,
    rate            NUMERIC(18, 8)  NOT NULL,
    version_id      BIGINT          NOT NULL,
    quality_state   VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_fx_rate PRIMARY KEY (id)
);

-- market_data.index_value
CREATE TABLE IF NOT EXISTS market_data.index_value (
    id                    BIGINT          NOT NULL DEFAULT nextval('market_data.index_value_seq'),
    tenant_id             VARCHAR         NOT NULL,
    series                VARCHAR         NOT NULL,
    ref_month_expression  VARCHAR         NOT NULL,
    value                 NUMERIC(18, 8)  NOT NULL,
    version_id            BIGINT          NOT NULL,
    quality_state         VARCHAR(20)     NOT NULL,
    created_at            TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_index_value PRIMARY KEY (id)
);

-- market_data.spread
CREATE TABLE IF NOT EXISTS market_data.spread (
    id              BIGINT          NOT NULL DEFAULT nextval('market_data.spread_seq'),
    tenant_id       VARCHAR         NOT NULL,
    series          VARCHAR         NOT NULL,
    interval_start  TIMESTAMPTZ     NOT NULL,
    value           NUMERIC(18, 8)  NOT NULL,
    version_id      BIGINT          NOT NULL,
    quality_state   VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_spread PRIMARY KEY (id)
);

-- market_data.vol_surface
CREATE TABLE IF NOT EXISTS market_data.vol_surface (
    id                  BIGINT              NOT NULL DEFAULT nextval('market_data.vol_surface_seq'),
    tenant_id           VARCHAR             NOT NULL,
    surface_id          VARCHAR             NOT NULL,
    strike_delta        DOUBLE PRECISION    NOT NULL,
    expiry_tenor        VARCHAR             NOT NULL,
    as_of_date          TIMESTAMPTZ         NOT NULL,
    implied_volatility  NUMERIC(18, 8)      NOT NULL,
    version_id          BIGINT              NOT NULL,
    quality_state       VARCHAR(20)         NOT NULL,
    created_at          TIMESTAMPTZ         NOT NULL,
    CONSTRAINT pk_vol_surface PRIMARY KEY (id)
);

-- trade.outbox
CREATE TABLE IF NOT EXISTS trade.outbox (
    id               BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    aggregate_type   VARCHAR(64) NOT NULL,
    aggregate_id     VARCHAR(64) NOT NULL,
    event_type       VARCHAR(64) NOT NULL,
    payload          JSONB       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    published_at     TIMESTAMPTZ,
    publish_attempts INT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox PRIMARY KEY (id)
);

-- position.position_ledger_entry
CREATE TABLE IF NOT EXISTS position.position_ledger_entry (
    id                  BIGINT          NOT NULL DEFAULT nextval('position.position_ledger_seq'),
    entry_uuid          UUID            NOT NULL,
    tenant_id           VARCHAR         NOT NULL,
    trade_id            VARCHAR(64)     NOT NULL,
    trade_leg_id        VARCHAR(64)     NOT NULL,
    trade_version       INT             NOT NULL,
    delivery_start      TIMESTAMPTZ     NOT NULL,
    delivery_end        TIMESTAMPTZ     NOT NULL,
    delivery_timezone   VARCHAR(64)     NOT NULL,
    quantity            NUMERIC(15, 8)  NOT NULL,
    volume_unit         VARCHAR(16)     NOT NULL,
    price_expression_id UUID            NOT NULL,
    volume_series_key   VARCHAR(128),
    multiplier          NUMERIC(15, 8)  NOT NULL DEFAULT 1,
    valid_from          TIMESTAMPTZ     NOT NULL,
    valid_to            TIMESTAMPTZ,
    known_from          TIMESTAMPTZ     NOT NULL,
    known_to            TIMESTAMPTZ,
    status              VARCHAR(16)     NOT NULL,
    cascade_parent_id   VARCHAR(64),
    cascade_generation  INT             NOT NULL DEFAULT 0,
    CONSTRAINT pk_position_ledger_entry  PRIMARY KEY (id),
    CONSTRAINT uq_ple_entry_uuid         UNIQUE (entry_uuid)
);

-- valuation.settlement_cell
CREATE TABLE IF NOT EXISTS valuation.settlement_cell (
    id                  BIGINT          NOT NULL DEFAULT nextval('valuation.settlement_cell_seq'),
    cell_uuid           UUID            NOT NULL,
    tenant_id           VARCHAR         NOT NULL,
    position_id         UUID            NOT NULL,
    interval_start      TIMESTAMPTZ     NOT NULL,
    interval_end        TIMESTAMPTZ     NOT NULL,
    valuation_type      VARCHAR(16)     NOT NULL,
    cell_status         VARCHAR(16)     NOT NULL,
    price               NUMERIC(15, 8)  NOT NULL,
    volume_mw           NUMERIC(15, 8)  NOT NULL,
    volume_mwh          NUMERIC(18, 8)  NOT NULL,
    amount              NUMERIC(18, 4)  NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    active_leaves       JSONB,
    input_version_set   JSONB           NOT NULL,
    valid_from          TIMESTAMPTZ     NOT NULL,
    valid_to            TIMESTAMPTZ,
    known_from          TIMESTAMPTZ     NOT NULL,
    known_to            TIMESTAMPTZ,
    CONSTRAINT pk_settlement_cell   PRIMARY KEY (id),
    CONSTRAINT uq_sc_cell_uuid      UNIQUE (cell_uuid)
);

-- valuation.struck_mark
CREATE TABLE IF NOT EXISTS valuation.struck_mark (
    id                  BIGINT          NOT NULL DEFAULT nextval('valuation.struck_mark_seq'),
    tenant_id           VARCHAR         NOT NULL,
    position_id         UUID            NOT NULL,
    delivery_month      VARCHAR(7)      NOT NULL,
    strike_date         DATE            NOT NULL,
    mark_value          NUMERIC(18, 4)  NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    curve_version_set   JSONB           NOT NULL,
    fx_version          VARCHAR(64),
    volume_version_set  JSONB,
    expression_version  BIGINT          NOT NULL,
    supersedes_id       BIGINT,
    created_at          TIMESTAMPTZ     NOT NULL,
    is_restrike         BOOLEAN         NOT NULL,
    CONSTRAINT pk_struck_mark PRIMARY KEY (id)
);

-- volume_series.volume_series
CREATE TABLE IF NOT EXISTS volume_series.volume_series (
    id                      BIGINT      NOT NULL DEFAULT nextval('volume_series.volume_series_seq'),
    series_uuid             UUID        NOT NULL,
    tenant_id               VARCHAR     NOT NULL,
    series_key              VARCHAR(128) NOT NULL,
    series_type             VARCHAR(32) NOT NULL,
    asset_id                VARCHAR(64),
    trade_leg_id            VARCHAR(64),
    version_id              BIGINT      NOT NULL,
    quality_state           VARCHAR(16) NOT NULL,
    materialization_status  VARCHAR(16) NOT NULL,
    transaction_time        TIMESTAMPTZ NOT NULL,
    valid_time              TIMESTAMPTZ,
    delivery_start          TIMESTAMPTZ,
    delivery_end            TIMESTAMPTZ,
    delivery_timezone       VARCHAR(64),
    CONSTRAINT pk_volume_series     PRIMARY KEY (id),
    CONSTRAINT uq_vs_series_uuid    UNIQUE (series_uuid)
);

-- volume_series.volume_interval
CREATE TABLE IF NOT EXISTS volume_series.volume_interval (
    id              BIGINT          NOT NULL DEFAULT nextval('volume_series.volume_interval_seq'),
    interval_uuid   UUID            NOT NULL,
    series_id       BIGINT          NOT NULL,
    tenant_id       VARCHAR         NOT NULL,
    interval_start  TIMESTAMPTZ     NOT NULL,
    interval_end    TIMESTAMPTZ     NOT NULL,
    volume          NUMERIC(15, 8)  NOT NULL,
    energy          NUMERIC(18, 8)  NOT NULL,
    version         INT             NOT NULL DEFAULT 1,
    supersedes_id   BIGINT,
    CONSTRAINT pk_volume_interval PRIMARY KEY (id),
    CONSTRAINT fk_vi_series
        FOREIGN KEY (series_id)
        REFERENCES volume_series.volume_series (id)
        ON DELETE CASCADE
);

-- volume_series.trade_interval_cache
CREATE TABLE IF NOT EXISTS volume_series.trade_interval_cache (
    id               BIGINT          NOT NULL DEFAULT nextval('volume_series.trade_interval_cache_seq'),
    tenant_id        VARCHAR         NOT NULL,
    trade_leg_id     VARCHAR(64)     NOT NULL,
    interval_start   TIMESTAMPTZ     NOT NULL,
    interval_end     TIMESTAMPTZ     NOT NULL,
    resolved_qty     NUMERIC(15, 8)  NOT NULL,
    resolved_energy  NUMERIC(18, 8)  NOT NULL,
    multiplier       NUMERIC(8, 8)   NOT NULL,
    series_key       VARCHAR(128)    NOT NULL,
    version_hash     VARCHAR(64)     NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_trade_interval_cache PRIMARY KEY (id)
);

-- ============================================================
-- 4. INDEXES — Entity @Index annotations
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_fix_tenant_series_start
    ON market_data.fixing (tenant_id, series, interval_start);

CREATE INDEX IF NOT EXISTS idx_fc_tenant_series_pillar_asof
    ON market_data.forward_curve (tenant_id, series, pillar, as_of_date DESC);

CREATE INDEX IF NOT EXISTS idx_fx_tenant_pair_refdate
    ON market_data.fx_rate (tenant_id, currency_pair, reference_date DESC);

CREATE INDEX IF NOT EXISTS idx_iv_tenant_series_refmonth
    ON market_data.index_value (tenant_id, series, ref_month_expression);

CREATE INDEX IF NOT EXISTS idx_spr_tenant_series_start
    ON market_data.spread (tenant_id, series, interval_start);

CREATE INDEX IF NOT EXISTS idx_vs_tenant_surface_strike_tenor_asof
    ON market_data.vol_surface (tenant_id, surface_id, strike_delta, expiry_tenor, as_of_date DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON trade.outbox (created_at);

CREATE INDEX IF NOT EXISTS idx_ple_tenant_trade
    ON position.position_ledger_entry (tenant_id, trade_id, trade_leg_id);
CREATE INDEX IF NOT EXISTS idx_ple_tenant_delivery
    ON position.position_ledger_entry (tenant_id, delivery_start, delivery_end);
CREATE INDEX IF NOT EXISTS idx_ple_current_knowledge
    ON position.position_ledger_entry (tenant_id, trade_id, known_to);
CREATE INDEX IF NOT EXISTS idx_ple_bitemporal
    ON position.position_ledger_entry (tenant_id, valid_from, valid_to, known_from, known_to);

CREATE INDEX IF NOT EXISTS idx_sc_position_interval
    ON valuation.settlement_cell (tenant_id, position_id, interval_start);
CREATE INDEX IF NOT EXISTS idx_sc_current_knowledge
    ON valuation.settlement_cell (tenant_id, position_id, known_to);
CREATE INDEX IF NOT EXISTS idx_sc_bitemporal
    ON valuation.settlement_cell (tenant_id, valid_from, valid_to, known_from, known_to);

CREATE INDEX IF NOT EXISTS idx_sm_position_month
    ON valuation.struck_mark (tenant_id, position_id, delivery_month, strike_date);
CREATE INDEX IF NOT EXISTS idx_sm_strike_date
    ON valuation.struck_mark (tenant_id, strike_date);

CREATE INDEX IF NOT EXISTS idx_vs_series_key_version
    ON volume_series.volume_series (tenant_id, series_key, version_id);
CREATE INDEX IF NOT EXISTS idx_vs_asset
    ON volume_series.volume_series (asset_id);
CREATE INDEX IF NOT EXISTS idx_vs_trade_leg
    ON volume_series.volume_series (trade_leg_id);

CREATE INDEX IF NOT EXISTS idx_tic_trade_leg_time
    ON volume_series.trade_interval_cache (trade_leg_id, interval_start);
CREATE INDEX IF NOT EXISTS idx_tic_tenant_time
    ON volume_series.trade_interval_cache (tenant_id, interval_start);

-- ============================================================
-- 5. PERFORMANCE INDEXES — Query-pattern optimizations
--    (beyond what @Index annotations provide)
-- ============================================================

-- Volume intervals: findCurrentBySeriesKeyAndRange() — was completely missing
CREATE INDEX IF NOT EXISTS idx_vi_series_range
    ON volume_series.volume_interval (series_id, interval_start, interval_end);

-- Fixings: covering index with version_id for ORDER BY
CREATE INDEX IF NOT EXISTS idx_fix_tenant_series_start_ver
    ON market_data.fixing (tenant_id, series, interval_start, version_id DESC);

-- Outbox: partial index for unpublished relay query
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_relay
    ON trade.outbox (created_at ASC) WHERE published_at IS NULL;

-- Settlement cells: partial index for current knowledge queries
CREATE INDEX IF NOT EXISTS idx_sc_position_interval_current
    ON valuation.settlement_cell (tenant_id, position_id, interval_start, interval_end)
    WHERE known_to IS NULL;

-- Position ledger: partial indexes for current knowledge queries
CREATE INDEX IF NOT EXISTS idx_ple_trade_leg_current
    ON position.position_ledger_entry (tenant_id, trade_id, trade_leg_id, delivery_start)
    WHERE known_to IS NULL;

CREATE INDEX IF NOT EXISTS idx_ple_delivery_range_current
    ON position.position_ledger_entry (tenant_id, delivery_start, delivery_end)
    WHERE known_to IS NULL;

-- Forward curve: covering index with version_id
CREATE INDEX IF NOT EXISTS idx_fc_tenant_series_pillar_asof_ver
    ON market_data.forward_curve (tenant_id, series, pillar, as_of_date DESC, version_id DESC);

-- Index values: covering index with version_id
CREATE INDEX IF NOT EXISTS idx_iv_tenant_series_refmonth_ver
    ON market_data.index_value (tenant_id, series, ref_month_expression, version_id DESC);
