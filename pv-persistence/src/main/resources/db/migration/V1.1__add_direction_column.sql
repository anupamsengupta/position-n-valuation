-- V1.1: Add trade direction (BUY/SELL) to position ledger and rollup tables.
-- FR-034, tech-spec buy-sell-trade-direction-v1.0 §S7.
--
-- Safe for online execution:
--   1. ADD COLUMN with DEFAULT avoids full table rewrite on PG 11+.
--   2. Backfill UPDATE is idempotent.
--   3. SET NOT NULL after backfill guarantees no nulls remain.

-- ============================================================
-- 1. position.position_ledger_entry — direction column
-- ============================================================
ALTER TABLE position.position_ledger_entry
    ADD COLUMN IF NOT EXISTS direction VARCHAR(4) DEFAULT 'BUY';

-- Backfill: infer direction from quantity sign for existing rows.
UPDATE position.position_ledger_entry
   SET direction = CASE WHEN quantity >= 0 THEN 'BUY' ELSE 'SELL' END
 WHERE direction IS NULL;

-- Now enforce NOT NULL.
ALTER TABLE position.position_ledger_entry
    ALTER COLUMN direction SET NOT NULL;

-- ============================================================
-- 2. volume_series.trade_leg_rollup_cell — direction column
-- ============================================================
ALTER TABLE volume_series.trade_leg_rollup_cell
    ADD COLUMN IF NOT EXISTS direction VARCHAR(4);

-- Backfill rollup direction from the owning position ledger entry.
UPDATE volume_series.trade_leg_rollup_cell rc
   SET direction = ple.direction
  FROM position.position_ledger_entry ple
 WHERE rc.tenant_id  = ple.tenant_id
   AND rc.trade_id   = ple.trade_id
   AND rc.trade_leg_id = ple.trade_leg_id
   AND rc.direction IS NULL;
