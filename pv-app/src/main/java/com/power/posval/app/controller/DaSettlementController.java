package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.DaSettlementGridDto;
import com.power.posval.app.dto.DaSettlementRowDto;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Simulator REST controller for the DA settlement grid (S9.4, S16.2.2, DA-UI-02).
 *
 * <p>{@code GET /api/da/settlement} returns interval-level settlement rows for all
 * positions in the requested bidding zone on the given delivery day, together with
 * summary statistics (total energy, total settlement amount, VWAP).
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/settlement")
public class DaSettlementController {

    private static final Logger log = LoggerFactory.getLogger(DaSettlementController.class);

    private static final ZoneId DEFAULT_TZ = ZoneId.of("Europe/Berlin");

    private final DaQueryService daQueryService;
    private final TransactionalExecutor txExecutor;

    public DaSettlementController(DaQueryService daQueryService,
                                   TransactionalExecutor txExecutor) {
        this.daQueryService = daQueryService;
        this.txExecutor     = txExecutor;
    }

    /**
     * Return the settlement grid for a delivery day and bidding zone (S16.2.2, DA-UI-02).
     *
     * <p>{@code GET /api/da/settlement?tenantId=&deliveryDay=&zone=&timezone=}
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code tenantId} — tenant identifier</li>
     *   <li>{@code deliveryDay} — ISO date, e.g. {@code 2026-08-22}</li>
     *   <li>{@code zone} — bidding zone identifier, e.g. {@code DE_LU}</li>
     *   <li>{@code timezone} — optional; defaults to {@code Europe/Berlin}</li>
     * </ul>
     */
    @GetMapping
    public ApiResponse<DaSettlementGridDto> getSettlement(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String deliveryDay,
            @RequestParam String zone,
            @RequestParam(required = false) String timezone) {

        LocalDate day = LocalDate.parse(deliveryDay);
        ZoneId tz = timezone != null ? ZoneId.of(timezone) : DEFAULT_TZ;

        log.info("GET /api/da/settlement tenantId={} day={} zone={} tz={}", tenantId, day, zone, tz);

        DaSettlementGridDto grid = txExecutor.execute(() -> {
            // Load positions indexed by positionId for fast lookup
            List<PositionLedgerEntry> positions =
                daQueryService.findPositionsForDay(tenantId, day, zone, tz);
            Map<UUID, PositionLedgerEntry> posById = positions.stream()
                .collect(Collectors.toMap(PositionLedgerEntry::id, p -> p, (a, b) -> a));

            // Load settlement cells
            List<SettlementCell> cells =
                daQueryService.findSettlementCellsForDay(tenantId, day, zone, tz);

            // Project cells to DTOs, joining position trade metadata
            List<DaSettlementRowDto> rows = new ArrayList<>(cells.size());
            for (SettlementCell cell : cells) {
                PositionLedgerEntry pos = posById.get(cell.positionId());
                String tradeId    = pos != null ? pos.tradeId()    : null;
                String tradeLegId = pos != null ? pos.tradeLegId() : null;
                String direction  = pos != null ? pos.direction().name() : null;
                rows.add(new DaSettlementRowDto(
                    cell.cellId(),
                    cell.positionId(),
                    tradeId,
                    tradeLegId,
                    direction,
                    cell.intervalStart(),
                    cell.intervalEnd(),
                    cell.price(),
                    cell.volumeMw(),
                    cell.volumeMwh(),
                    cell.amount(),
                    cell.marketPrice(),
                    cell.marketAmount(),
                    cell.pnl(),
                    cell.currency(),
                    cell.cellStatus(),
                    cell.valuationType()
                ));
            }

            // Compute summary: total energy, total settlement, VWAP
            BigDecimal totalMwh    = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            String currency        = null;
            for (DaSettlementRowDto row : rows) {
                if (row.volumeMwh() != null) totalMwh    = totalMwh.add(row.volumeMwh());
                if (row.amount()    != null) totalAmount = totalAmount.add(row.amount());
                if (currency == null && row.currency() != null) currency = row.currency();
            }

            BigDecimal vwap = null;
            if (totalMwh.compareTo(BigDecimal.ZERO) != 0) {
                vwap = totalAmount.divide(totalMwh, 8, RoundingMode.HALF_UP);
            }

            return new DaSettlementGridDto(
                day, zone, rows, totalMwh, totalAmount, vwap, currency
            );
        });

        log.info("GET /api/da/settlement => {} rows", grid.rows().size());
        return ApiResponse.ok(grid);
    }
}
