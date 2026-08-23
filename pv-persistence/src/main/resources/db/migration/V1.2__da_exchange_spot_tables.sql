-- ============================================================
-- V1.2 — Day-Ahead Exchange Spot tables
-- Spec refs: S9.1, S9.2, DA-VOL, DA-SET, DA-OPS
-- Creates the "da" schema, 9 sequences, 10 tables, 7 indexes.
-- Idempotent: IF NOT EXISTS throughout.
-- ============================================================

-- 1. SCHEMA
CREATE SCHEMA IF NOT EXISTS da;

-- 2. SEQUENCES (allocationSize=50 matches Hibernate @SequenceGenerator)
CREATE SEQUENCE IF NOT EXISTS da.auction_import_session_seq  INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.balancing_group_seq         INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.asset_to_bg_mapping_seq     INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.block_definition_seq        INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.exchange_fee_schedule_seq   INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.holiday_calendar_seq        INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.imbalance_record_seq        INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.nomination_record_seq       INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS da.operational_alert_seq       INCREMENT BY 50 START WITH 1;

-- 3. TABLES

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

-- 4. INDEXES (entity @Index annotations)

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
