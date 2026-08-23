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
CREATE SCHEMA IF NOT EXISTS da;

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

CREATE SEQUENCE IF NOT EXISTS da.auction_import_session_seq  INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.balancing_group_seq         INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.asset_to_bg_mapping_seq     INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.block_definition_seq        INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.exchange_fee_schedule_seq   INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.holiday_calendar_seq        INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.imbalance_record_seq        INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.nomination_record_seq       INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.operational_alert_seq       INCREMENT BY 50 START WITH 1;

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
    trade_id            VARCHAR(255)    NOT NULL,
    trade_leg_id        VARCHAR(255)     NOT NULL,
    trade_version       INT             NOT NULL,
    delivery_start      TIMESTAMPTZ     NOT NULL,
    delivery_end        TIMESTAMPTZ     NOT NULL,
    delivery_timezone   VARCHAR(64)     NOT NULL,
    quantity            NUMERIC(15, 8)  NOT NULL,
    volume_unit         VARCHAR(16)     NOT NULL,
    price_expression_id UUID            NOT NULL,
    market_price_expression_id UUID,
    portfolio_id        VARCHAR(64),
    delivery_point_id   VARCHAR(64),
    volume_series_key   VARCHAR(128),
    multiplier          NUMERIC(15, 8)  NOT NULL DEFAULT 1,
    valid_from          TIMESTAMPTZ     NOT NULL,
    valid_to            TIMESTAMPTZ,
    known_from          TIMESTAMPTZ     NOT NULL,
    known_to            TIMESTAMPTZ,
    status              VARCHAR(16)     NOT NULL,
    direction           VARCHAR(4)      NOT NULL DEFAULT 'BUY',
    cascade_parent_id   VARCHAR(64),
    cascade_generation  INT             NOT NULL DEFAULT 0,
    CONSTRAINT pk_position_ledger_entry  PRIMARY KEY (id),
    CONSTRAINT uq_ple_entry_uuid         UNIQUE (entry_uuid)
);

-- valuation.settlement_cell
-- Versioning is derived from the parent position entry's bitemporal state.
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
    market_price        NUMERIC(15, 8),
    market_amount       NUMERIC(18, 4),
    pnl                 NUMERIC(18, 4),
    currency            VARCHAR(3)      NOT NULL,
    active_leaves       JSONB,
    input_version_set   JSONB           NOT NULL,
    computed_at         TIMESTAMPTZ     NOT NULL,
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

-- valuation.dependency_edge (S8 — reverse-dependency index for blast-radius optimization, FR-102–104)
CREATE TABLE IF NOT EXISTS valuation.dependency_edge (
    tenant_id               VARCHAR         NOT NULL,
    cell_id                 UUID            NOT NULL,
    cell_type               VARCHAR(32)     NOT NULL,
    input_series_key        VARCHAR(128)    NOT NULL,
    input_type              VARCHAR(32)     NOT NULL,
    affected_range_start    TIMESTAMPTZ     NOT NULL,
    affected_range_end      TIMESTAMPTZ     NOT NULL,
    active_leaves           JSONB           NOT NULL DEFAULT '[]',
    created_at              TIMESTAMPTZ     NOT NULL,
    pruned_at               TIMESTAMPTZ,
    CONSTRAINT pk_dependency_edge PRIMARY KEY (tenant_id, cell_id, input_series_key)
);

-- volume_series.volume_series
CREATE TABLE IF NOT EXISTS volume_series.volume_series (
    id                      BIGINT      NOT NULL DEFAULT nextval('volume_series.volume_series_seq'),
    series_uuid             UUID        NOT NULL,
    tenant_id               VARCHAR     NOT NULL,
    series_key              VARCHAR(128) NOT NULL,
    series_type             VARCHAR(32) NOT NULL,
    asset_id                VARCHAR(64),
    trade_leg_id            VARCHAR(255),
    version_id              BIGINT      NOT NULL,
    time_granularity        VARCHAR(16) NOT NULL,
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
    trade_leg_id     VARCHAR(255)    NOT NULL,
    interval_start   TIMESTAMPTZ     NOT NULL,
    interval_end     TIMESTAMPTZ     NOT NULL,
    resolved_qty     NUMERIC(15, 8)  NOT NULL,
    resolved_energy  NUMERIC(18, 8)  NOT NULL,
    multiplier       NUMERIC(15, 8)  NOT NULL,
    series_key       VARCHAR(128)    NOT NULL,
    version_hash     VARCHAR(64)     NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_trade_interval_cache PRIMARY KEY (id)
);

-- volume_series.rollup_cell (S7 — materialized rollup aggregates, no JPA entity, native SQL only)
CREATE TABLE IF NOT EXISTS volume_series.rollup_cell (
    tenant_id           VARCHAR         NOT NULL,
    delivery_point_id   VARCHAR         NOT NULL,
    portfolio_id        VARCHAR         NOT NULL,
    interval_start      TIMESTAMPTZ     NOT NULL,
    interval_end        TIMESTAMPTZ     NOT NULL,
    granularity         VARCHAR(16)     NOT NULL,
    net_mw              NUMERIC(15, 8)  NOT NULL,
    net_mwh             NUMERIC(18, 8)  NOT NULL,
    is_peak             BOOLEAN         NOT NULL,
    price               NUMERIC(15, 8),
    market_price        NUMERIC(15, 8),
    settled_value       NUMERIC(18, 4),
    market_value        NUMERIC(18, 4),
    pnl                 NUMERIC(18, 4),
    forward_mark_value  NUMERIC(18, 4),
    currency            VARCHAR(3),
    calendar_version    VARCHAR,
    version_hash        VARCHAR,
    refreshed_at        TIMESTAMPTZ,
    CONSTRAINT uq_rollup_cell
        UNIQUE (tenant_id, delivery_point_id, portfolio_id,
                interval_start, granularity, is_peak)
);

-- volume_series.trade_leg_rollup_cell (S7 — per-position materialized rollup, native SQL only)
CREATE TABLE IF NOT EXISTS volume_series.trade_leg_rollup_cell (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id               VARCHAR(64)     NOT NULL,
    position_id             UUID            NOT NULL,
    trade_id                VARCHAR(128)    NOT NULL,
    trade_leg_id            VARCHAR(128)    NOT NULL,
    trade_version           INTEGER         NOT NULL,
    delivery_point_id       VARCHAR(128)    NOT NULL,
    portfolio_id            VARCHAR(128)    NOT NULL,
    period_start            TIMESTAMPTZ     NOT NULL,
    period_end              TIMESTAMPTZ     NOT NULL,
    granularity             VARCHAR(16)     NOT NULL,
    settled_mw              NUMERIC(18, 8)  NOT NULL,
    settled_mwh             NUMERIC(18, 8)  NOT NULL,
    avg_price               NUMERIC(18, 8)  NOT NULL,
    settled_value           NUMERIC(18, 4)  NOT NULL,
    market_value            NUMERIC(18, 4)  NOT NULL,
    realized_pnl            NUMERIC(18, 4)  NOT NULL,
    has_forward_intervals   BOOLEAN         NOT NULL,
    delivery_status         VARCHAR(16)     NOT NULL,
    quantity                NUMERIC(18, 8)  NOT NULL,
    volume_unit             VARCHAR(32),
    direction               VARCHAR(4),
    currency                VARCHAR(3)      NOT NULL DEFAULT 'EUR',
    version_hash            VARCHAR(64),
    refreshed_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_trade_leg_rollup_cell PRIMARY KEY (id),
    CONSTRAINT uq_tlr_position_period_gran
        UNIQUE (tenant_id, position_id, period_start, granularity)
);

-- da.target2_calendar (natural DATE PK — no sequence)
CREATE TABLE IF NOT EXISTS da.target2_calendar (
    calendar_date   DATE        NOT NULL,
    is_business_day BOOLEAN     NOT NULL,
    CONSTRAINT pk_target2_calendar PRIMARY KEY (calendar_date)
);

-- da.holiday_calendar
CREATE TABLE IF NOT EXISTS da.holiday_calendar (
    id              BIGINT          NOT NULL DEFAULT nextval('da.holiday_calendar_seq'),
    calendar_id     UUID            NOT NULL,
    zone            VARCHAR(8)      NOT NULL,
    holiday_date    DATE            NOT NULL,
    holiday_name    VARCHAR(128)    NOT NULL,
    CONSTRAINT pk_holiday_calendar PRIMARY KEY (id),
    CONSTRAINT uq_hc_calendar_id   UNIQUE (calendar_id),
    CONSTRAINT uq_hc_zone_date     UNIQUE (zone, holiday_date)
);
-- da.balancing_group
CREATE TABLE IF NOT EXISTS da.balancing_group (
    id              BIGINT          NOT NULL DEFAULT nextval('da.balancing_group_seq'),
    bg_id           UUID            NOT NULL,
    tenant_id       VARCHAR(64)     NOT NULL,
    tso_area        VARCHAR(32)     NOT NULL,
    bg_code         VARCHAR(64)     NOT NULL,
    active_from     DATE            NOT NULL,
    active_to       DATE,
    CONSTRAINT pk_balancing_group   PRIMARY KEY (id),
    CONSTRAINT uq_bg_bg_id         UNIQUE (bg_id),
    CONSTRAINT uq_bg_tenant_code   UNIQUE (tenant_id, bg_code)
);

-- da.asset_to_bg_mapping
CREATE TABLE IF NOT EXISTS da.asset_to_bg_mapping (
    id                  BIGINT          NOT NULL DEFAULT nextval('da.asset_to_bg_mapping_seq'),
    mapping_id          UUID            NOT NULL,
    tenant_id           VARCHAR(64)     NOT NULL,
    delivery_point_id   VARCHAR(64)     NOT NULL,
    balancing_group_id  UUID            NOT NULL,
    effective_from      DATE            NOT NULL,
    effective_to        DATE,
    CONSTRAINT pk_asset_to_bg_mapping  PRIMARY KEY (id),
    CONSTRAINT uq_abgm_mapping_id     UNIQUE (mapping_id)
);

-- da.block_definition
CREATE TABLE IF NOT EXISTS da.block_definition (
    id                    BIGINT          NOT NULL DEFAULT nextval('da.block_definition_seq'),
    block_id              UUID            NOT NULL,
    exchange              VARCHAR(32)     NOT NULL,
    block_type            VARCHAR(16)     NOT NULL,
    bidding_zone          VARCHAR(16),
    start_hour            TIME            NOT NULL,
    end_hour              TIME            NOT NULL,
    applicable_days       VARCHAR(32)     NOT NULL,
    holiday_calendar_ref  VARCHAR(8),
    effective_from        DATE            NOT NULL,
    effective_to          DATE,
    CONSTRAINT pk_block_definition  PRIMARY KEY (id),
    CONSTRAINT uq_bd_block_id      UNIQUE (block_id)
);

-- da.exchange_fee_schedule
CREATE TABLE IF NOT EXISTS da.exchange_fee_schedule (
    id              BIGINT          NOT NULL DEFAULT nextval('da.exchange_fee_schedule_seq'),
    schedule_id     UUID            NOT NULL,
    tenant_id       VARCHAR(64)     NOT NULL,
    exchange        VARCHAR(32)     NOT NULL,
    fee_type        VARCHAR(16)     NOT NULL,
    rate_per_mwh    NUMERIC(18, 8)  NOT NULL,
    member_tier     VARCHAR(32),
    effective_from  DATE            NOT NULL,
    effective_to    DATE,
    CONSTRAINT pk_exchange_fee_schedule  PRIMARY KEY (id),
    CONSTRAINT uq_efs_schedule_id       UNIQUE (schedule_id)
);

-- da.auction_import_session
CREATE TABLE IF NOT EXISTS da.auction_import_session (
    id                              BIGINT          NOT NULL DEFAULT nextval('da.auction_import_session_seq'),
    session_id                      UUID            NOT NULL,
    tenant_id                       VARCHAR(64)     NOT NULL,
    exchange                        VARCHAR(32)     NOT NULL,
    bidding_zone                    VARCHAR(16)     NOT NULL,
    delivery_day                    DATE            NOT NULL,
    import_timestamp                TIMESTAMPTZ     NOT NULL,
    status                          VARCHAR(32)     NOT NULL,
    exchange_reported_total_mwh     NUMERIC(18, 8),
    imported_total_mwh              NUMERIC(18, 8),
    interval_count                  INTEGER,
    file_reference                  VARCHAR(512),
    trade_ids                       JSONB,
    validation_errors               JSONB,
    created_at                      TIMESTAMPTZ     NOT NULL,
    completed_at                    TIMESTAMPTZ,
    CONSTRAINT pk_auction_import_session         PRIMARY KEY (id),
    CONSTRAINT uq_ais_session_id                 UNIQUE (session_id),
    CONSTRAINT uq_ais_tenant_exchange_zone_day   UNIQUE (tenant_id, exchange, bidding_zone, delivery_day)
);

-- da.nomination_record
CREATE TABLE IF NOT EXISTS da.nomination_record (
    id                      BIGINT          NOT NULL DEFAULT nextval('da.nomination_record_seq'),
    nomination_id           UUID            NOT NULL,
    tenant_id               VARCHAR(64)     NOT NULL,
    balancing_group_id      VARCHAR(64)     NOT NULL,
    delivery_day            DATE            NOT NULL,
    interval_start          TIMESTAMPTZ     NOT NULL,
    interval_end            TIMESTAMPTZ     NOT NULL,
    nominated_volume_mw     NUMERIC(18, 8)  NOT NULL,
    nomination_timestamp    TIMESTAMPTZ     NOT NULL,
    nomination_version      INTEGER         NOT NULL,
    submitted_by            VARCHAR(128),
    CONSTRAINT pk_nomination_record     PRIMARY KEY (id),
    CONSTRAINT uq_nr_nomination_id      UNIQUE (nomination_id)
);

-- da.imbalance_record
CREATE TABLE IF NOT EXISTS da.imbalance_record (
    id                          BIGINT          NOT NULL DEFAULT nextval('da.imbalance_record_seq'),
    record_id                   UUID            NOT NULL,
    tenant_id                   VARCHAR(64)     NOT NULL,
    balancing_group_id          VARCHAR(64)     NOT NULL,
    delivery_day                DATE            NOT NULL,
    interval_start              TIMESTAMPTZ     NOT NULL,
    interval_end                TIMESTAMPTZ     NOT NULL,
    nominated_volume_mw         NUMERIC(18, 8),
    actual_delivered_mw         NUMERIC(18, 8),
    imbalance_volume_mw         NUMERIC(18, 8),
    imbalance_energy_mwh        NUMERIC(18, 8),
    imbalance_price_mwh         NUMERIC(18, 8),
    imbalance_amount            NUMERIC(18, 4),
    currency                    VARCHAR(3)      NOT NULL,
    tso_data_source             VARCHAR(128),
    tso_publication_timestamp   TIMESTAMPTZ,
    record_version              INTEGER         NOT NULL,
    computed_at                 TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_imbalance_record  PRIMARY KEY (id),
    CONSTRAINT uq_ir_record_id      UNIQUE (record_id)
);

-- da.operational_alert
CREATE TABLE IF NOT EXISTS da.operational_alert (
    id                  BIGINT          NOT NULL DEFAULT nextval('da.operational_alert_seq'),
    alert_id            UUID            NOT NULL,
    tenant_id           VARCHAR(64)     NOT NULL,
    category            VARCHAR(32)     NOT NULL,
    severity            VARCHAR(16)     NOT NULL,
    alert_type          VARCHAR(64)     NOT NULL,
    message             TEXT            NOT NULL,
    delivery_day        DATE,
    bidding_zone        VARCHAR(16),
    source_event_id     VARCHAR(128),
    raised_at           TIMESTAMPTZ     NOT NULL,
    acknowledged_by     VARCHAR(128),
    acknowledged_at     TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    status              VARCHAR(16)     NOT NULL,
    CONSTRAINT pk_operational_alert  PRIMARY KEY (id),
    CONSTRAINT uq_oa_alert_id       UNIQUE (alert_id)
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
CREATE INDEX IF NOT EXISTS idx_sc_position
    ON valuation.settlement_cell (tenant_id, position_id);

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

CREATE INDEX IF NOT EXISTS idx_ais_tenant_status
    ON da.auction_import_session (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_abgm_tenant_dp_from
    ON da.asset_to_bg_mapping (tenant_id, delivery_point_id, effective_from);

CREATE INDEX IF NOT EXISTS idx_efs_tenant_exchange_type_from
    ON da.exchange_fee_schedule (tenant_id, exchange, fee_type, effective_from);

CREATE INDEX IF NOT EXISTS idx_ir_tenant_bg_day
    ON da.imbalance_record (tenant_id, balancing_group_id, delivery_day);
CREATE INDEX IF NOT EXISTS idx_ir_tenant_bg_start
    ON da.imbalance_record (tenant_id, balancing_group_id, interval_start);

CREATE INDEX IF NOT EXISTS idx_nr_tenant_bg_day_start
    ON da.nomination_record (tenant_id, balancing_group_id, delivery_day, interval_start);

CREATE INDEX IF NOT EXISTS idx_oa_tenant_status_sev_raised
    ON da.operational_alert (tenant_id, status, severity, raised_at);

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

-- Settlement cells: covering index for range queries
CREATE INDEX IF NOT EXISTS idx_sc_position_interval_range
    ON valuation.settlement_cell (tenant_id, position_id, interval_start, interval_end);

-- Dependency edges: range-overlap lookup for revaluation blast-radius (FR-103)
CREATE INDEX IF NOT EXISTS idx_de_series_range
    ON valuation.dependency_edge (tenant_id, input_series_key, affected_range_start, affected_range_end)
    WHERE pruned_at IS NULL;

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

-- Dependency edges: GIN index for JSONB active_leaves containment queries (FR-103)
CREATE INDEX IF NOT EXISTS idx_de_active_leaves_gin
    ON valuation.dependency_edge USING GIN (active_leaves)
    WHERE pruned_at IS NULL;

-- Rollup cells: range-overlap query for findByRange()
CREATE INDEX IF NOT EXISTS idx_rc_tenant_dp_port_range
    ON volume_series.rollup_cell (tenant_id, delivery_point_id, portfolio_id, interval_start, interval_end, granularity);

-- Trade-leg rollup: L3 query Q-10 findByPortfolio() — covers WHERE + ORDER BY
CREATE INDEX IF NOT EXISTS idx_tlr_portfolio_granularity_time
    ON volume_series.trade_leg_rollup_cell (tenant_id, portfolio_id, granularity, period_start, trade_leg_id);

-- Trade-leg rollup: deleteByPositionId (orphan cleanup on amendment/cancel)
CREATE INDEX IF NOT EXISTS idx_tlr_position
    ON volume_series.trade_leg_rollup_cell (tenant_id, position_id);

-- Trade-leg rollup: lookup by trade_id (all rollups for a given trade)
CREATE INDEX IF NOT EXISTS idx_tlr_trade
    ON volume_series.trade_leg_rollup_cell (tenant_id, trade_id);

-- Trade-leg rollup: lookup by trade_leg_id (rollups for a specific leg)
CREATE INDEX IF NOT EXISTS idx_tlr_trade_leg
    ON volume_series.trade_leg_rollup_cell (tenant_id, trade_leg_id);

-- Trade-leg rollup: delivery point filtering within portfolio queries
CREATE INDEX IF NOT EXISTS idx_tlr_delivery_point
    ON volume_series.trade_leg_rollup_cell (tenant_id, delivery_point_id, period_start);

