package com.power.posval.domain.port.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port interface for forward mark computation on demand.
 *
 * <p>Per ADR-002: forward marks are computed at query time from S4 forward curves
 * × S6b resolved volumes. There is no persistent S5b store. Evaluated monthly
 * prices are Redis-cached with short TTL, keyed by
 * {@code (expressionId, curveVersionHash, month)}.
 *
 * <p>D-3: Forward marks are ephemeral (computed, not stored). D-13: No Spring
 * types. Pattern #18 (Port + Adapter), ADR-002.
 */
public interface ForwardMarkService {

    /**
     * Compute MtM for a position-month.
     *
     * <p>Used by L3 {@code positionContributions()} to obtain forwardMarkValue
     * per position. Monthly curve prices are Redis-cached; computation is O(1)
     * on cache hit.
     *
     * @param tenantId   tenant identifier (multi-tenancy, D-14)
     * @param positionId position identifier from S1
     * @param monthStart UTC instant for month start
     * @param monthEnd   UTC instant for month end (exclusive)
     * @return MonthlyMark with forwardMtm, totalMwh, avgPrice, curveId, curveVersion
     */
    MonthlyMark computeMonthlyMark(String tenantId, UUID positionId,
                                    Instant monthStart, Instant monthEnd);

    /**
     * Compute MtM per interval for a position-day.
     *
     * <p>Used by L4 {@code forwardDayDetail()} to obtain per-interval
     * evaluatedPrice and markValue. Loads S6b volumes and evaluates price
     * expression against S4 curves.
     *
     * @param tenantId   tenant identifier
     * @param positionId position identifier from S1
     * @param dayStart   UTC instant for day start
     * @param dayEnd     UTC instant for day end (exclusive)
     * @return list of IntervalMark records, one per S6b interval in the day range
     */
    List<IntervalMark> computeIntervalMarks(String tenantId, UUID positionId,
                                             Instant dayStart, Instant dayEnd);

    /**
     * Compute portfolio-level MtM for a period.
     *
     * <p>Used by the rollup pipeline to populate {@code forwardMarkValue} on
     * rollup cells. Sums {@code computeMonthlyMark()} across all positions in
     * the portfolio for the period.
     *
     * @param tenantId    tenant identifier
     * @param portfolioId portfolio identifier
     * @param periodStart UTC instant for period start
     * @param periodEnd   UTC instant for period end (exclusive)
     * @return total forward MtM for the portfolio in the period, or zero if
     *         no positions / no S6b data
     */
    BigDecimal computePortfolioMtm(String tenantId, String portfolioId,
                                    Instant periodStart, Instant periodEnd);
}
