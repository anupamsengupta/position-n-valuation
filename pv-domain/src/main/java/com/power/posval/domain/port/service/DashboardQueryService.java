package com.power.posval.domain.port.service;

import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.service.dashboard.DailyAggregate;
import com.power.posval.domain.port.service.dashboard.ForwardIntervalDetail;
import com.power.posval.domain.port.service.dashboard.PortfolioSummary;
import com.power.posval.domain.port.service.dashboard.PositionContribution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only query facade for the position and PnL dashboard (L1–L4).
 *
 * <p>Composite reads span S1 (Position Ledger), S4/ForwardMarkService, S5a
 * (Settlement Cells), S6b (Trade Interval Cache), and S7 (Rollups). FR-035
 * aggregation (TWA for MW, sum for MWh, volume-weighted average for prices)
 * is applied server-side.
 *
 * <p>Every method accepts {@code tenantId} as the first parameter.
 * Downstream repository and service calls propagate tenantId per Pattern #32
 * (multi-tenancy) and D-14 (no hardcoded tenant IDs in library modules).
 *
 * <p>D-13: No Spring types. Pattern #18 (Port + Adapter), §5.1.
 */
public interface DashboardQueryService {

    // --- L0: Portfolio List ---

    /**
     * Distinct portfolio IDs for a tenant, derived from current-knowledge
     * ACTIVE position ledger entries.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @return distinct portfolio IDs, ordered alphabetically
     */
    List<String> listPortfolios(String tenantId);

    // --- L1: Portfolio Cards ---

    /**
     * Q-1: Aggregate rollup cells across all delivery points for a portfolio.
     * Groups by currency. Returns one {@link PortfolioSummary} per currency.
     *
     * @param tenantId           tenant identifier (D-14, Pattern #32)
     * @param portfolioId        portfolio identifier
     * @param rangeStart         delivery range start (UTC, inclusive)
     * @param rangeEnd           delivery range end (UTC, exclusive)
     * @param rollupGranularity  typically MONTHLY or YEARLY
     * @return one PortfolioSummary per currency in the range
     */
    List<PortfolioSummary> portfolioSummaries(String tenantId,
                                               String portfolioId,
                                               Instant rangeStart,
                                               Instant rangeEnd,
                                               TimeGranularity rollupGranularity);

    // --- L2: Period Grid ---

    /**
     * Q-1 variant: Return raw rollup cells for a portfolio across all delivery
     * points. Clients render the grid directly.
     *
     * @param tenantId    tenant identifier
     * @param portfolioId portfolio identifier
     * @param rangeStart  delivery range start (UTC, inclusive)
     * @param rangeEnd    delivery range end (UTC, exclusive)
     * @param granularity DAILY | WEEKLY | MONTHLY | YEARLY
     * @return rollup cells ordered by intervalStart
     */
    List<RollupCell> rollupGrid(String tenantId,
                                 String portfolioId,
                                 Instant rangeStart,
                                 Instant rangeEnd,
                                 TimeGranularity granularity);

    // --- L3: Trade-Level View ---

    /**
     * Q-2 + Q-9: Position contributions for a portfolio within a delivery range.
     *
     * <p>Hybrid bulk-fetch approach (§6.5):
     * <ol>
     *   <li>1 SQL: {@code PositionLedgerRepository.findByPortfolioAndDeliveryRange()}</li>
     *   <li>1 SQL: {@code SettlementCellRepository.findByPositionIds()} bulk</li>
     *   <li>1 SQL: {@code TradeIntervalCache.getForTradeLegIds()} bulk</li>
     *   <li>~N Redis: {@code ForwardMarkService.computeMonthlyMark()} per position</li>
     * </ol>
     * FR-035 aggregation applied per position in Java.
     *
     * @param tenantId    tenant identifier
     * @param portfolioId portfolio identifier
     * @param periodStart period start (UTC, inclusive)
     * @param periodEnd   period end (UTC, exclusive)
     * @return position contributions with settled actuals and forward marks
     */
    List<PositionContribution> positionContributions(String tenantId,
                                                      String portfolioId,
                                                      Instant periodStart,
                                                      Instant periodEnd);

    // --- L4: Month View ---

    /**
     * Q-4: Daily aggregates for a position or portfolio within a month.
     *
     * <p>Prefers DAILY rollup cells (S7) when available; falls back to on-the-fly
     * aggregation from S5a (settled) and ForwardMarkService (forward, ADR-002),
     * grouped by CET/CEST day boundaries.
     *
     * @param tenantId    tenant identifier
     * @param portfolioId portfolio identifier
     * @param positionId  nullable — null means portfolio-scoped
     * @param monthStart  UTC start of month
     * @param monthEnd    UTC end of month (exclusive)
     * @param timezone    CET/CEST timezone for day boundary computation
     *                    (default "Europe/Berlin")
     * @return daily aggregates ordered by dayStart
     */
    List<DailyAggregate> dailyAggregates(String tenantId,
                                          String portfolioId,
                                          UUID positionId,
                                          Instant monthStart,
                                          Instant monthEnd,
                                          String timezone);

    // --- L4: Settled Day View ---

    /**
     * Q-3 + Q-5: Settlement cells for a position or portfolio on a single day,
     * optionally aggregated to a coarser sub-daily granularity.
     *
     * <p>For MIN_30 and HOURLY, cells are aggregated server-side per FR-035
     * (TWA for MW, sum for MWh/amounts, volume-weighted avg for prices).
     *
     * @param tenantId           tenant identifier
     * @param portfolioId        portfolio identifier
     * @param positionId         nullable — null means portfolio-scoped
     * @param dayStart           UTC day start
     * @param dayEnd             UTC day end (exclusive)
     * @param subDailyGranularity MIN_15 | MIN_30 | HOURLY
     * @return settlement cells (raw or aggregated) ordered by intervalStart
     */
    List<SettlementCell> settledDayDetail(String tenantId,
                                           String portfolioId,
                                           UUID positionId,
                                           Instant dayStart,
                                           Instant dayEnd,
                                           TimeGranularity subDailyGranularity);

    // --- L4: Forward Day View ---

    /**
     * Q-6 + Q-7: Forward interval detail for a position or portfolio on a single day,
     * optionally aggregated.
     *
     * <p>Calls {@code ForwardMarkService.computeIntervalMarks()} per position (ADR-002),
     * then applies FR-035 sub-daily aggregation if granularity is MIN_30 or HOURLY.
     *
     * @param tenantId           tenant identifier
     * @param portfolioId        portfolio identifier
     * @param positionId         nullable — null means portfolio-scoped
     * @param dayStart           UTC day start
     * @param dayEnd             UTC day end (exclusive)
     * @param subDailyGranularity MIN_15 | MIN_30 | HOURLY
     * @return forward interval details ordered by intervalStart
     */
    List<ForwardIntervalDetail> forwardDayDetail(String tenantId,
                                                  String portfolioId,
                                                  UUID positionId,
                                                  Instant dayStart,
                                                  Instant dayEnd,
                                                  TimeGranularity subDailyGranularity);
}
