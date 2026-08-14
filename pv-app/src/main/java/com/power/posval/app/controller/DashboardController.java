package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.RollupCellDto;
import com.power.posval.app.dto.SettlementCellDto;
import com.power.posval.app.dto.dashboard.DailyAggregateDto;
import com.power.posval.app.dto.dashboard.ForwardIntervalDetailDto;
import com.power.posval.app.dto.dashboard.PortfolioSummaryDto;
import com.power.posval.app.dto.dashboard.PositionContributionDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.service.DashboardQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard REST endpoints (simulator-scope, pv-app).
 *
 * <p>Serves all four dashboard levels (L1–L4) via {@link DashboardQueryService},
 * which is obtained from the Guice {@code Injector} per D-13.
 *
 * <p>All queries are read-only and wrapped in a read transaction via
 * {@link TransactionalExecutor}. In a production host, read-only transactions
 * would route to the reader DataSource (Pattern #22, #28 CQRS).
 *
 * <p>tenantId is taken from the query parameter (simulator convenience; in a
 * production host it would come from the authenticated JWT / X-Tenant-Id header).
 * D-14: hardcoded "default" tenant is NOT used here; tenantId is always supplied
 * by the caller.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;
    private final TransactionalExecutor txExecutor;

    public DashboardController(DashboardQueryService dashboardQueryService,
                                TransactionalExecutor txExecutor) {
        this.dashboardQueryService = dashboardQueryService;
        this.txExecutor = txExecutor;
    }

    // -------------------------------------------------------------------------
    // A.1: L1 -- Portfolio Cards
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios/{portfolioId}/summary
     * Returns one PortfolioSummaryDto per currency.
     */
    @GetMapping("/portfolios/{portfolioId}/summary")
    public ApiResponse<List<PortfolioSummaryDto>> portfolioSummary(
            @PathVariable String portfolioId,
            @RequestParam String tenantId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd,
            @RequestParam(defaultValue = "MONTHLY") String granularity) {
        var summaries = txExecutor.execute(
            () -> dashboardQueryService.portfolioSummaries(
                tenantId, portfolioId,
                Instant.parse(rangeStart), Instant.parse(rangeEnd),
                TimeGranularity.valueOf(granularity)));
        return ApiResponse.ok(summaries.stream().map(PortfolioSummaryDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.2: L2 -- Period Grid
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios/{portfolioId}/rollups
     * Returns raw rollup cells across all delivery points for the portfolio.
     * Reuses existing {@link RollupCellDto}.
     */
    @GetMapping("/portfolios/{portfolioId}/rollups")
    public ApiResponse<List<RollupCellDto>> rollupGrid(
            @PathVariable String portfolioId,
            @RequestParam String tenantId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd,
            @RequestParam(defaultValue = "MONTHLY") String granularity) {
        var cells = txExecutor.execute(
            () -> dashboardQueryService.rollupGrid(
                tenantId, portfolioId,
                Instant.parse(rangeStart), Instant.parse(rangeEnd),
                TimeGranularity.valueOf(granularity)));
        return ApiResponse.ok(cells.stream().map(RollupCellDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.3: L3 -- Trade-Level View
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios/{portfolioId}/positions
     * Returns position contributions (settled actuals + forward marks) per position.
     * Offset-based pagination (bounded set, §S10a).
     */
    @GetMapping("/portfolios/{portfolioId}/positions")
    public ApiResponse<List<PositionContributionDto>> positionContributions(
            @PathVariable String portfolioId,
            @RequestParam String tenantId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        var contributions = txExecutor.execute(
            () -> dashboardQueryService.positionContributions(
                tenantId, portfolioId,
                Instant.parse(periodStart), Instant.parse(periodEnd)));

        int clampedLimit = Math.min(limit, 200);
        int fromIdx = Math.min(offset, contributions.size());
        int toIdx = Math.min(fromIdx + clampedLimit, contributions.size());
        List<PositionContributionDto> page = contributions.subList(fromIdx, toIdx)
            .stream()
            .map(PositionContributionDto::from)
            .toList();

        return ApiResponse.ok(page);
    }

    // -------------------------------------------------------------------------
    // A.4: L4 -- Month View (daily aggregates)
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios/{portfolioId}/daily
     * Returns daily aggregates for a position or portfolio within a month.
     * positionId is optional (null = portfolio-scoped).
     */
    @GetMapping("/portfolios/{portfolioId}/daily")
    public ApiResponse<List<DailyAggregateDto>> dailyAggregates(
            @PathVariable String portfolioId,
            @RequestParam String tenantId,
            @RequestParam String monthStart,
            @RequestParam String monthEnd,
            @RequestParam(required = false) String positionId,
            @RequestParam(defaultValue = "Europe/Berlin") String timezone) {
        UUID posId = positionId != null ? UUID.fromString(positionId) : null;
        var aggregates = txExecutor.execute(
            () -> dashboardQueryService.dailyAggregates(
                tenantId, portfolioId, posId,
                Instant.parse(monthStart), Instant.parse(monthEnd),
                timezone));
        return ApiResponse.ok(aggregates.stream().map(DailyAggregateDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.5: L4 -- Settled Day View
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/settlements/day
     * Returns settlement cells for a day, optionally aggregated to MIN_30 or HOURLY.
     * Reuses existing {@link SettlementCellDto} shape for all granularities.
     */
    @GetMapping("/settlements/day")
    public ApiResponse<List<SettlementCellDto>> settledDayDetail(
            @RequestParam String tenantId,
            @RequestParam String portfolioId,
            @RequestParam(required = false) String positionId,
            @RequestParam String dayStart,
            @RequestParam String dayEnd,
            @RequestParam(defaultValue = "MIN_15") String granularity) {
        UUID posId = positionId != null ? UUID.fromString(positionId) : null;
        var cells = txExecutor.execute(
            () -> dashboardQueryService.settledDayDetail(
                tenantId, portfolioId, posId,
                Instant.parse(dayStart), Instant.parse(dayEnd),
                TimeGranularity.valueOf(granularity)));
        return ApiResponse.ok(cells.stream().map(SettlementCellDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.6: L4 -- Forward Day View
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/forward/day
     * Returns forward interval details for a day, optionally aggregated.
     * markType = "INDICATIVE" per S11 EMIR labeling requirement.
     */
    @GetMapping("/forward/day")
    public ApiResponse<List<ForwardIntervalDetailDto>> forwardDayDetail(
            @RequestParam String tenantId,
            @RequestParam String portfolioId,
            @RequestParam(required = false) String positionId,
            @RequestParam String dayStart,
            @RequestParam String dayEnd,
            @RequestParam(defaultValue = "MIN_15") String granularity) {
        UUID posId = positionId != null ? UUID.fromString(positionId) : null;
        var details = txExecutor.execute(
            () -> dashboardQueryService.forwardDayDetail(
                tenantId, portfolioId, posId,
                Instant.parse(dayStart), Instant.parse(dayEnd),
                TimeGranularity.valueOf(granularity)));
        return ApiResponse.ok(details.stream().map(ForwardIntervalDetailDto::from).toList());
    }
}
