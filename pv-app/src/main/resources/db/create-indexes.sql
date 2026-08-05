-- Performance indexes for pv-app JPA query patterns.
-- Complements the @Index annotations on JPA entities (created by Hibernate hbm2ddl).
-- All use IF NOT EXISTS for idempotency.

-- ============================================================
-- volume_series.volume_interval
-- findCurrentBySeriesKeyAndRange(): WHERE series_id = ? AND interval_start < ? AND interval_end > ?
-- This table has NO indexes by default (no @Index on the entity).
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_vi_series_range
    ON volume_series.volume_interval (series_id, interval_start, interval_end);

-- ============================================================
-- market_data.fixing
-- findFixing(): WHERE tenant_id, series, interval_start ORDER BY version_id DESC LIMIT 1
-- Existing index: (tenant_id, series, interval_start) — add version_id for covering
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_fix_tenant_series_start_ver
    ON market_data.fixing (tenant_id, series, interval_start, version_id DESC);

-- ============================================================
-- trade.outbox
-- OutboxRelayProducer.relay(): WHERE published_at IS NULL ORDER BY created_at ASC LIMIT 100
-- Existing index on (created_at) is not partial — add partial index for unpublished only
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_relay
    ON trade.outbox (created_at ASC) WHERE published_at IS NULL;

-- ============================================================
-- valuation.settlement_cell
-- findByPosition(): WHERE tenant_id, position_id, interval_start < ?, interval_end > ?, known_to IS NULL
-- Existing: (tenant_id, position_id, interval_start) — add partial index for current knowledge
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_sc_position_interval_current
    ON valuation.settlement_cell (tenant_id, position_id, interval_start, interval_end)
    WHERE known_to IS NULL;

-- ============================================================
-- position.position_ledger_entry
-- findCurrentByTradeLeg(): WHERE tenant_id, trade_id, trade_leg_id, known_to IS NULL
-- Existing: (tenant_id, trade_id, trade_leg_id) — add partial for current knowledge
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_ple_trade_leg_current
    ON position.position_ledger_entry (tenant_id, trade_id, trade_leg_id, delivery_start)
    WHERE known_to IS NULL;

-- findAllByDeliveryRange(): WHERE tenant_id, delivery_start < ?, delivery_end > ?, known_to IS NULL
-- Existing: (tenant_id, delivery_start, delivery_end) — add partial
CREATE INDEX IF NOT EXISTS idx_ple_delivery_range_current
    ON position.position_ledger_entry (tenant_id, delivery_start, delivery_end)
    WHERE known_to IS NULL;

-- ============================================================
-- market_data.forward_curve
-- findForwardCurve(): WHERE tenant_id, series, pillar, as_of_date <= ? ORDER BY as_of_date DESC, version_id DESC
-- Existing: (tenant_id, series, pillar, as_of_date DESC) — add version_id for covering
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_fc_tenant_series_pillar_asof_ver
    ON market_data.forward_curve (tenant_id, series, pillar, as_of_date DESC, version_id DESC);

-- ============================================================
-- market_data.index_value
-- findIndex(): WHERE tenant_id, series, ref_month_expression ORDER BY version_id DESC
-- Existing: (tenant_id, series, ref_month_expression) — add version_id for covering
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_iv_tenant_series_refmonth_ver
    ON market_data.index_value (tenant_id, series, ref_month_expression, version_id DESC);
