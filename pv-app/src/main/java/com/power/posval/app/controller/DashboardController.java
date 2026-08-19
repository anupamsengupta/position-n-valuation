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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard REST endpoints (simulator-scope, pv-app).
 *
 * <p>Serves all four dashboard levels (L1-L4) via {@link DashboardQueryService},
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

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardQueryService dashboardQueryService;
    private final TransactionalExecutor txExecutor;

    public DashboardController(DashboardQueryService dashboardQueryService,
                                TransactionalExecutor txExecutor) {
        this.dashboardQueryService = dashboardQueryService;
        this.txExecutor = txExecutor;
    }

    // -------------------------------------------------------------------------
    // A.0: Portfolio List
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios
     * Returns distinct portfolio IDs from current-knowledge ACTIVE positions.
     */
    @GetMapping("/portfolios")
    public ApiResponse<List<String>> listPortfolios(@RequestParam String tenantId) {
        log.info("GET /api/dashboard/portfolios tenantId={}", tenantId);
        List<String> portfolios = txExecutor.execute(
            () -> dashboardQueryService.listPortfolios(tenantId));
        log.info("GET /api/dashboard/portfolios => {} portfolios names {}", portfolios.size(), portfolios);
        return ApiResponse.ok(portfolios);
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
        log.info("GET /api/dashboard/portfolios/{}/summary tenantId={} range=[{} .. {}] granularity={}",
            portfolioId, tenantId, rangeStart, rangeEnd, granularity);
        var summaries = txExecutor.execute(
            () -> dashboardQueryService.portfolioSummaries(
                tenantId, portfolioId,
                Instant.parse(rangeStart), Instant.parse(rangeEnd),
                TimeGranularity.valueOf(granularity)));
        log.info("GET /api/dashboard/portfolios/{}/summary => {} currency cards", portfolioId, summaries.size());
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
        log.info("GET /api/dashboard/portfolios/{}/rollups tenantId={} range=[{} .. {}] granularity={}",
            portfolioId, tenantId, rangeStart, rangeEnd, granularity);
        var cells = txExecutor.execute(
            () -> dashboardQueryService.rollupGrid(
                tenantId, portfolioId,
                Instant.parse(rangeStart), Instant.parse(rangeEnd),
                TimeGranularity.valueOf(granularity)));
        log.info("GET /api/dashboard/portfolios/{}/rollups => {} cells", portfolioId, cells.size());
        return ApiResponse.ok(cells.stream().map(RollupCellDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.3: L3 -- Trade-Level View
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios/{portfolioId}/positions
     * Returns position contributions (settled actuals + forward marks) per position.
     * Offset-based pagination (bounded set, S10a).
     */
    @GetMapping("/portfolios/{portfolioId}/positions")
    public ApiResponse<List<PositionContributionDto>> positionContributions(
            @PathVariable String portfolioId,
            @RequestParam String tenantId,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        log.info("GET /api/dashboard/portfolios/{}/positions tenantId={} period=[{} .. {}] offset={} limit={}",
            portfolioId, tenantId, periodStart, periodEnd, offset, limit);
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

        log.info("GET /api/dashboard/portfolios/{}/positions => {} total, returning [{} .. {}]",
            portfolioId, contributions.size(), fromIdx, toIdx);
        return ApiResponse.ok(page);
    }

    // -------------------------------------------------------------------------
    // A.4: L4 -- Month View (daily aggregates)
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/portfolios/{portfolioId}/daily
     * Returns daily aggregates for a position or portfolio within a month.
     *
     * <p>S15.2.2: {@code positionId} is a repeatable parameter. Spring MVC binds
     * multiple {@code ?positionId=uuid-1&positionId=uuid-2} values to a
     * {@code List<String>}. Backward compatibility: a single
     * {@code ?positionId=uuid} still works (list of one). No {@code positionId}
     * → portfolio-scoped. D-13: no Spring types in the service layer.
     */
    @GetMapping("/portfolios/{portfolioId}/daily")
    public ApiResponse<List<DailyAggregateDto>> dailyAggregates(
            @PathVariable String portfolioId,
            @RequestParam String tenantId,
            @RequestParam String monthStart,
            @RequestParam String monthEnd,
            @RequestParam(required = false) List<String> positionId,
            @RequestParam(defaultValue = "Europe/Berlin") String timezone) {
        log.info("GET /api/dashboard/portfolios/{}/daily tenantId={} month=[{} .. {}] positionIds={} tz={}",
            portfolioId, tenantId, monthStart, monthEnd, positionId, timezone);

        List<UUID> posIds = parsePositionIds(positionId);
        var aggregates = txExecutor.execute(
            () -> dashboardQueryService.dailyAggregates(
                tenantId, portfolioId, posIds,
                Instant.parse(monthStart), Instant.parse(monthEnd),
                timezone));
        log.info("GET /api/dashboard/portfolios/{}/daily => {} days", portfolioId, aggregates.size());
        return ApiResponse.ok(aggregates.stream().map(DailyAggregateDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.5: L4 -- Settled Day View
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/settlements/day
     * Returns settlement cells for a day or contiguous day range, optionally
     * aggregated to MIN_30 or HOURLY. Reuses existing {@link SettlementCellDto}.
     *
     * <p>S15.2.2: {@code positionId} is repeatable. {@code dayEnd} may be multiple
     * days after {@code dayStart} for contiguous day ranges. Ranges exceeding 31 days
     * are rejected with HTTP 400 per S15.2.3. D-13: service layer remains Spring-free.
     */
    @GetMapping("/settlements/day")
    public ApiResponse<List<SettlementCellDto>> settledDayDetail(
            @RequestParam String tenantId,
            @RequestParam String portfolioId,
            @RequestParam(required = false) List<String> positionId,
            @RequestParam String dayStart,
            @RequestParam String dayEnd,
            @RequestParam(defaultValue = "MIN_15") String granularity) {
        log.info("GET /api/dashboard/settlements/day tenantId={} portfolio={} positionIds={} day=[{} .. {}] granularity={}",
            tenantId, portfolioId, positionId, dayStart, dayEnd, granularity);

        Instant start = Instant.parse(dayStart);
        Instant end = Instant.parse(dayEnd);
        validateDayRange(start, end);

        List<UUID> posIds = parsePositionIds(positionId);
        var cells = txExecutor.execute(
            () -> dashboardQueryService.settledDayDetail(
                tenantId, portfolioId, posIds,
                start, end,
                TimeGranularity.valueOf(granularity)));
        log.info("GET /api/dashboard/settlements/day => {} cells", cells.size());
        return ApiResponse.ok(cells.stream().map(SettlementCellDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // A.6: L4 -- Forward Day View
    // -------------------------------------------------------------------------

    /**
     * GET /api/dashboard/forward/day
     * Returns forward interval details for a day or contiguous day range,
     * optionally aggregated. markType = "INDICATIVE" per S11 EMIR labeling.
     *
     * <p>S15.2.2: {@code positionId} is repeatable. {@code dayEnd} may be multiple
     * days after {@code dayStart} for contiguous day ranges. Ranges exceeding 31 days
     * are rejected with HTTP 400 per S15.2.3. D-13: service layer remains Spring-free.
     */
    @GetMapping("/forward/day")
    public ApiResponse<List<ForwardIntervalDetailDto>> forwardDayDetail(
            @RequestParam String tenantId,
            @RequestParam String portfolioId,
            @RequestParam(required = false) List<String> positionId,
            @RequestParam String dayStart,
            @RequestParam String dayEnd,
            @RequestParam(defaultValue = "MIN_15") String granularity) {
        log.info("GET /api/dashboard/forward/day tenantId={} portfolio={} positionIds={} day=[{} .. {}] granularity={}",
            tenantId, portfolioId, positionId, dayStart, dayEnd, granularity);

        Instant start = Instant.parse(dayStart);
        Instant end = Instant.parse(dayEnd);
        validateDayRange(start, end);

        List<UUID> posIds = parsePositionIds(positionId);
        var details = txExecutor.execute(
            () -> dashboardQueryService.forwardDayDetail(
                tenantId, portfolioId, posIds,
                start, end,
                TimeGranularity.valueOf(granularity)));
        log.info("GET /api/dashboard/forward/day => {} intervals", details.size());
        return ApiResponse.ok(details.stream().map(ForwardIntervalDetailDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a list of raw positionId strings (from repeatable query param) to
     * a list of UUIDs. Null or empty input → empty list, which the service layer
     * interprets as portfolio-scoped. S15.2.2.
     */
    private List<UUID> parsePositionIds(List<String> positionId) {
        if (positionId == null || positionId.isEmpty()) {
            return Collections.emptyList();
        }
        return positionId.stream().map(UUID::fromString).toList();
    }

    /**
     * Rejects day ranges exceeding 31 days with HTTP 400 per S15.2.3.
     * Protects against unbounded queries on {@code /settlements/day} and
     * {@code /forward/day}.
     */
    private void validateDayRange(Instant start, Instant end) {
        if (Duration.between(start, end).toDays() > 31) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Day range must not exceed 31 days");
        }
    }
}
