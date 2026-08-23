-- V1.3  Widen trade_id / trade_leg_id columns for DA exchange spot trade IDs.
-- DA trade IDs follow the pattern EXCHANGE/ZONE/DATE/CONTRACT/interval-N
-- which exceeds the original VARCHAR(64) limit.

ALTER TABLE position.position_ledger_entry
    ALTER COLUMN trade_id      TYPE VARCHAR(255),
    ALTER COLUMN trade_leg_id  TYPE VARCHAR(255);

ALTER TABLE volume_series.volume_series
    ALTER COLUMN trade_leg_id  TYPE VARCHAR(255);

ALTER TABLE volume_series.trade_interval_cache
    ALTER COLUMN trade_leg_id  TYPE VARCHAR(255);
