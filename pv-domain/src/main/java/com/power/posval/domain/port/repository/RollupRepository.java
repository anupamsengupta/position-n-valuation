package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.TimeGranularity;

import java.time.Instant;
import java.util.List;

/**
 * Port interface for rollup queries (S7).
 * Rollups serve grid requests beyond the hot window and regulatory extracts.
 * FR-090, FR-091.
 */
public interface RollupRepository {

    /** Read rollup cells for a tenant within a delivery range at a given granularity. */
    List<RollupCell> findByRange(String tenantId,
                                  String deliveryPointId,
                                  String portfolioId,
                                  Instant rangeStart,
                                  Instant rangeEnd,
                                  TimeGranularity granularity);

    /**
     * Refresh rollup cells from source data (slot cache + settlement cells).
     * FR-105 step 4: called during batch cycle.
     */
    void refresh(String tenantId,
                  Instant rangeStart,
                  Instant rangeEnd,
                  TimeGranularity granularity);

    /**
     * Persist or upsert rollup cells computed by RollupMaterializationService.
     * FR-090: per (delivery_point, portfolio) × period.
     */
    default void saveAll(String tenantId, List<RollupCell> cells) {
        throw new UnsupportedOperationException("saveAll not implemented");
    }

    /**
     * Q-1: Rollup cells for a portfolio across ALL delivery points within a
     * date range at the specified granularity.
     *
     * <p>Distinct from {@link #findByRange} which requires a specific
     * {@code deliveryPointId}. This method queries across all delivery points
     * belonging to the portfolio, using index
     * {@code idx_rollup_portfolio_granularity_time}.
     *
     * <p>Pattern #18 (Repository Port + Adapter), §5.2.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param portfolioId portfolio identifier
     * @param rangeStart  interval start filter (UTC, exclusive upper bound on
     *                    interval_end)
     * @param rangeEnd    interval end filter (UTC, exclusive lower bound on
     *                    interval_start)
     * @param granularity DAILY | WEEKLY | MONTHLY | YEARLY
     * @return rollup cells ordered by intervalStart
     */
    default List<RollupCell> findByPortfolio(String tenantId,
                                               String portfolioId,
                                               Instant rangeStart,
                                               Instant rangeEnd,
                                               TimeGranularity granularity) {
        throw new UnsupportedOperationException("findByPortfolio not implemented");
    }
}
