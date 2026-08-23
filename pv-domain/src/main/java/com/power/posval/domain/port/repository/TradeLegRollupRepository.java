package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.TimeGranularity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port interface for trade-leg rollup persistence (S7).
 *
 * <p>Stores pre-computed per-position aggregates at DAILY and MONTHLY granularities.
 * The materialization pipeline writes via {@code saveAll()}; the dashboard query
 * path reads via {@code findByPortfolio()} (Q-10).
 *
 * <p>All methods require {@code tenantId} as the first parameter per Pattern #32 and D-14.
 * No hardcoded tenant IDs in library modules.
 *
 * <p>Pattern #18 (Repository Port + Adapter). S7, FR-035, D-14.
 */
public interface TradeLegRollupRepository {

    /**
     * Q-10: Trade-leg rollup cells for a portfolio within a delivery range.
     *
     * <p>Returns one row per position per period at the requested granularity.
     * Ordered by periodStart, tradeLegId.
     *
     * <p>Uses index {@code idx_tlr_portfolio_granularity_time} on
     * {@code (tenant_id, portfolio_id, granularity, period_start)}.
     *
     * <p>Pattern #18, #32. S7, FR-035.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param portfolioId portfolio identifier
     * @param rangeStart  period start filter (UTC, exclusive upper bound on period_end)
     * @param rangeEnd    period end filter (UTC, exclusive lower bound on period_start)
     * @param granularity DAILY or MONTHLY
     * @return rollup cells ordered by periodStart, tradeLegId
     */
    List<TradeLegRollupCell> findByPortfolio(
            String tenantId,
            String portfolioId,
            Instant rangeStart,
            Instant rangeEnd,
            TimeGranularity granularity);

    /**
     * Persist or upsert trade-leg rollup cells.
     *
     * <p>Upsert key: {@code (tenant_id, position_id, period_start, granularity)}.
     * Uses {@code INSERT ... ON CONFLICT DO UPDATE} — idempotent by construction.
     *
     * <p>Pattern #18. S7.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param cells    cells to upsert (may be empty — no-op)
     */
    void saveAll(String tenantId, List<TradeLegRollupCell> cells);

    /**
     * Delete stale rollups for a position (e.g. on trade amendment/cancellation).
     *
     * <p>When a position is superseded (bitemporal close), a new position UUID is assigned.
     * The old position's rollup rows become orphaned and must be cleaned up explicitly
     * (S8.4, Option A).
     *
     * <p>Pattern #18. S7.
     *
     * @param tenantId   tenant identifier (D-14, Pattern #32)
     * @param positionId the superseded position UUID
     */
    void deleteByPositionId(String tenantId, UUID positionId);
}
