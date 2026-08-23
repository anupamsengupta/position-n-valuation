package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.DaKpiDto;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.value.ExchangeFeeResult;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.service.DaQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * Simulator REST controller for the DA KPI summary panel (S16.2.2, DA-UI-07).
 *
 * <p>{@code GET /api/da/kpi} aggregates key metrics for a delivery day and bidding zone:
 * net traded volume (MWh), VWAP, settlement total, exchange fees, imbalance cost,
 * open alert count, and maximum alert severity.
 *
 * <p>The aggregation is performed in the controller from individually fetched data sets;
 * no new repository calls are introduced beyond those already backed by {@link DaQueryService}.
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/kpi")
public class DaKpiController {

    private static final Logger log = LoggerFactory.getLogger(DaKpiController.class);

    private static final ZoneId DEFAULT_TZ = ZoneId.of("Europe/Berlin");

    /** Severity ordering: CRITICAL > WARNING > INFO — ordinal() is increasing, so we use it. */
    private static final Comparator<AlertSeverity> SEVERITY_ORDER =
        Comparator.comparingInt(Enum::ordinal);

    private final DaQueryService daQueryService;
    private final TransactionalExecutor txExecutor;

    public DaKpiController(DaQueryService daQueryService,
                            TransactionalExecutor txExecutor) {
        this.daQueryService = daQueryService;
        this.txExecutor     = txExecutor;
    }

    /**
     * KPI summary for a delivery day and bidding zone (S16.2.2, DA-UI-07).
     *
     * <p>{@code GET /api/da/kpi?tenantId=&deliveryDay=&zone=}
     *
     * <p>Aggregates:
     * <ul>
     *   <li>{@code netVolumeMwh} — algebraic sum of all settlement cell MWh in the zone</li>
     *   <li>{@code vwap} — volume-weighted average price across all cells; null if no energy</li>
     *   <li>{@code settlementTotal} — sum of all cell amounts in the zone</li>
     *   <li>{@code exchangeFees} — total fee amount from EPEX_SPOT fee computation</li>
     *   <li>{@code imbalanceCost} — null in this endpoint (imbalance is keyed to BG, not zone)</li>
     *   <li>{@code openAlertCount} — count of OPEN alerts scoped to the delivery day</li>
     *   <li>{@code maxAlertSeverity} — highest severity among open alerts for the day</li>
     *   <li>{@code currency} — taken from the first settlement cell; null if no data</li>
     * </ul>
     */
    @GetMapping
    public ApiResponse<DaKpiDto> getKpi(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String deliveryDay,
            @RequestParam String zone) {

        LocalDate day = LocalDate.parse(deliveryDay);
        log.info("GET /api/da/kpi tenantId={} day={} zone={}", tenantId, day, zone);

        DaKpiDto kpi = txExecutor.execute(() -> {
            // --- Settlement metrics ---
            List<SettlementCell> cells =
                daQueryService.findSettlementCellsForDay(tenantId, day, zone, DEFAULT_TZ);

            BigDecimal netVolumeMwh    = BigDecimal.ZERO;
            BigDecimal settlementTotal = BigDecimal.ZERO;
            String currency            = null;
            for (SettlementCell cell : cells) {
                if (cell.volumeMwh() != null) netVolumeMwh    = netVolumeMwh.add(cell.volumeMwh());
                if (cell.amount()    != null) settlementTotal = settlementTotal.add(cell.amount());
                if (currency == null && cell.currency() != null) currency = cell.currency();
            }

            BigDecimal vwap = null;
            if (netVolumeMwh.compareTo(BigDecimal.ZERO) != 0) {
                vwap = settlementTotal.divide(netVolumeMwh, 8, RoundingMode.HALF_UP);
            }

            // --- Exchange fees ---
            BigDecimal exchangeFees;
            try {
                ExchangeFeeResult feeResult = daQueryService.findFeesForDay(tenantId, day);
                exchangeFees = feeResult.totalFeeAmount();
                if (currency == null) currency = feeResult.currency();
            } catch (Exception e) {
                log.warn("GET /api/da/kpi fee computation failed, defaulting to zero: {}", e.getMessage());
                exchangeFees = BigDecimal.ZERO;
            }

            // --- Imbalance cost (zone-scoped KPI uses null — imbalance is BG-scoped) ---
            final BigDecimal imbalanceCost = null;

            // --- Alerts for the delivery day ---
            List<OperationalAlert> dayAlerts = daQueryService.findAlerts(tenantId, day);
            long openAlertCount = dayAlerts.size();
            String maxAlertSeverity = dayAlerts.stream()
                .map(OperationalAlert::severity)
                .min(SEVERITY_ORDER)
                .map(Enum::name)
                .orElse(null);

            return new DaKpiDto(
                day, zone, netVolumeMwh, vwap, settlementTotal,
                exchangeFees, imbalanceCost, openAlertCount, maxAlertSeverity, currency
            );
        });

        log.info("GET /api/da/kpi => {}", kpi);
        return ApiResponse.ok(kpi);
    }
}
