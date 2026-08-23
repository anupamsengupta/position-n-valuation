package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.DaImbalanceDailyDto;
import com.power.posval.app.dto.DaImbalanceMonthlyDto;
import com.power.posval.app.dto.DaImbalanceRecordDto;
import com.power.posval.app.dto.DaImbalanceRequest;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.value.ImbalanceDaySummary;
import com.power.posval.domain.model.value.ImbalanceMonthSummary;
import com.power.posval.domain.port.service.DaQueryService;
import com.power.posval.domain.port.service.ImbalanceSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Simulator REST controller for DA imbalance settlement (S9.4, DA-SET-04, DA-UI-04, S16.2.2).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/da/imbalance} — compute and persist imbalance for a day</li>
 *   <li>{@code GET  /api/da/imbalance/daily} — read daily imbalance detail grid</li>
 *   <li>{@code GET  /api/da/imbalance/monthly} — read monthly imbalance summary</li>
 * </ul>
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/imbalance")
public class DaImbalanceController {

    private static final Logger log = LoggerFactory.getLogger(DaImbalanceController.class);

    private final ImbalanceSettlementService imbalanceSettlementService;
    private final DaQueryService daQueryService;
    private final TransactionalExecutor txExecutor;

    public DaImbalanceController(ImbalanceSettlementService imbalanceSettlementService,
                                  DaQueryService daQueryService,
                                  TransactionalExecutor txExecutor) {
        this.imbalanceSettlementService = imbalanceSettlementService;
        this.daQueryService             = daQueryService;
        this.txExecutor                 = txExecutor;
    }

    /**
     * Compute imbalance settlement for a balancing group on a delivery day (S9.4, DA-SET-04).
     *
     * <p>{@code POST /api/da/imbalance}
     */
    @PostMapping
    public ApiResponse<List<DaImbalanceRecordDto>> computeImbalance(
            @RequestBody DaImbalanceRequest request) {
        log.info("POST /api/da/imbalance tenantId={} bgId={} day={}",
            request.tenantId(), request.balancingGroupId(), request.deliveryDay());
        List<ImbalanceRecord> records = txExecutor.execute(
            () -> imbalanceSettlementService.computeForDay(request.toCommand()));
        log.info("POST /api/da/imbalance => {} imbalance records computed", records.size());
        List<DaImbalanceRecordDto> dtos = records.stream().map(this::toImbalanceDto).toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * Daily imbalance detail grid (S16.2.2, DA-UI-04).
     *
     * <p>{@code GET /api/da/imbalance/daily?tenantId=&deliveryDay=&balancingGroupId=}
     *
     * <p>Returns per-interval imbalance rows for the given delivery day and balancing group,
     * together with daily net MWh and monetary totals.
     */
    @GetMapping("/daily")
    public ApiResponse<DaImbalanceDailyDto> getDailyImbalance(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String deliveryDay,
            @RequestParam String balancingGroupId) {

        LocalDate day = LocalDate.parse(deliveryDay);
        log.info("GET /api/da/imbalance/daily tenantId={} day={} bgId={}",
            tenantId, day, balancingGroupId);

        List<ImbalanceRecord> records = txExecutor.execute(
            () -> daQueryService.findImbalanceForDay(tenantId, day, balancingGroupId));

        // Project to row DTOs
        List<DaImbalanceDailyDto.Row> rows = records.stream()
            .map(r -> new DaImbalanceDailyDto.Row(
                r.recordId(),
                r.intervalStart(),
                r.intervalEnd(),
                r.nominatedVolumeMw(),
                r.actualDeliveredMw(),
                r.imbalanceVolumeMw(),
                r.imbalanceEnergyMwh(),
                r.imbalancePricePerMwh(),
                r.imbalanceAmount(),
                r.recordVersion()
            ))
            .toList();

        // Compute daily net totals
        BigDecimal netMwh    = records.stream()
            .map(ImbalanceRecord::imbalanceEnergyMwh)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netAmount = records.stream()
            .map(ImbalanceRecord::imbalanceAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = records.isEmpty() ? null : records.get(0).currency();

        DaImbalanceDailyDto dto = new DaImbalanceDailyDto(
            day, balancingGroupId, rows, netMwh, netAmount, currency
        );

        log.info("GET /api/da/imbalance/daily => {} intervals netMwh={}", rows.size(), netMwh);
        return ApiResponse.ok(dto);
    }

    /**
     * Monthly imbalance summary (S16.2.2, DA-UI-04).
     *
     * <p>{@code GET /api/da/imbalance/monthly?tenantId=&yearMonth=&balancingGroupId=}
     *
     * <p>Returns per-day breakdown and month-level net MWh and monetary totals.
     * {@code yearMonth} must be in ISO format, e.g. {@code 2026-08}.
     */
    @GetMapping("/monthly")
    public ApiResponse<DaImbalanceMonthlyDto> getMonthlyImbalance(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String yearMonth,
            @RequestParam String balancingGroupId) {

        YearMonth month = YearMonth.parse(yearMonth);
        log.info("GET /api/da/imbalance/monthly tenantId={} month={} bgId={}",
            tenantId, month, balancingGroupId);

        ImbalanceMonthSummary summary = txExecutor.execute(
            () -> daQueryService.findImbalanceMonthly(tenantId, month, balancingGroupId));

        List<DaImbalanceMonthlyDto.DayRow> dayRows = summary.dailyBreakdown().stream()
            .map(d -> new DaImbalanceMonthlyDto.DayRow(
                d.day(), d.imbalanceMwh(), d.imbalanceAmount()))
            .toList();

        DaImbalanceMonthlyDto dto = new DaImbalanceMonthlyDto(
            month.toString(),
            summary.balancingGroupId(),
            dayRows,
            summary.netImbalanceMwh(),
            summary.netImbalanceAmount(),
            summary.currency()
        );

        log.info("GET /api/da/imbalance/monthly => {} days netMwh={}",
            dayRows.size(), summary.netImbalanceMwh());
        return ApiResponse.ok(dto);
    }

    private DaImbalanceRecordDto toImbalanceDto(ImbalanceRecord r) {
        return new DaImbalanceRecordDto(
            r.recordId(),
            r.tenantId(),
            r.balancingGroupId(),
            r.intervalStart(),
            r.intervalEnd(),
            r.nominatedVolumeMw(),
            r.actualDeliveredMw(),
            r.imbalanceVolumeMw(),
            r.imbalanceEnergyMwh(),
            r.imbalancePricePerMwh(),
            r.imbalanceAmount(),
            r.currency(),
            r.tsoDataSource(),
            r.tsoPublicationTimestamp(),
            r.recordVersion(),
            r.computedAt(),
            r.deliveryDay()
        );
    }
}
