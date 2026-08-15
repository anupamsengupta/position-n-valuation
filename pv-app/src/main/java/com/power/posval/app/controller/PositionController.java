package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.PositionLedgerEntryDto;
import com.power.posval.app.dto.PositionMonthSummaryDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.service.PositionQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private static final Logger log = LoggerFactory.getLogger(PositionController.class);

    private final PositionQueryService positionService;
    private final TransactionalExecutor txExecutor;

    public PositionController(PositionQueryService positionService,
                               TransactionalExecutor txExecutor) {
        this.positionService = positionService;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<PositionLedgerEntryDto>> findCurrent(
            @RequestParam String tenantId,
            @RequestParam String tradeId,
            @RequestParam String tradeLegId) {
        log.info("GET /api/positions tenantId={} tradeId={} tradeLegId={}", tenantId, tradeId, tradeLegId);
        var entries = txExecutor.execute(
                () -> positionService.findCurrent(tenantId, tradeId, tradeLegId));
        log.info("GET /api/positions => {} entries", entries.size());
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/as-of")
    public ApiResponse<List<PositionLedgerEntryDto>> findAsOf(
            @RequestParam String tenantId,
            @RequestParam String tradeId,
            @RequestParam String tradeLegId,
            @RequestParam String businessDate,
            @RequestParam String knowledgeDate) {
        log.info("GET /api/positions/as-of tenantId={} tradeId={} tradeLegId={} businessDate={} knowledgeDate={}",
            tenantId, tradeId, tradeLegId, businessDate, knowledgeDate);
        var entries = txExecutor.execute(
                () -> positionService.findAsOf(tenantId, tradeId, tradeLegId,
                        Instant.parse(businessDate), Instant.parse(knowledgeDate)));
        log.info("GET /api/positions/as-of => {} entries", entries.size());
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/by-range")
    public ApiResponse<List<PositionLedgerEntryDto>> findByDeliveryRange(
            @RequestParam String tenantId,
            @RequestParam String deliveryStart,
            @RequestParam String deliveryEnd) {
        log.info("GET /api/positions/by-range tenantId={} delivery=[{} .. {}]", tenantId, deliveryStart, deliveryEnd);
        var entries = txExecutor.execute(
                () -> positionService.findByDeliveryRange(tenantId,
                        Instant.parse(deliveryStart), Instant.parse(deliveryEnd)));
        log.info("GET /api/positions/by-range => {} entries", entries.size());
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/summary")
    public ApiResponse<List<PositionMonthSummaryDto>> monthlySummary(
            @RequestParam String tenantId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd,
            @RequestParam(required = false) String positionId) {
        log.info("GET /api/positions/summary tenantId={} range=[{} .. {}] positionId={}",
            tenantId, rangeStart, rangeEnd, positionId);
        var summaries = txExecutor.execute(() -> {
            if (positionId != null) {
                return positionService.monthlySummaryByPosition(tenantId,
                        UUID.fromString(positionId),
                        Instant.parse(rangeStart), Instant.parse(rangeEnd));
            }
            return positionService.monthlySummary(tenantId,
                    Instant.parse(rangeStart), Instant.parse(rangeEnd));
        });
        log.info("GET /api/positions/summary => {} summaries", summaries.size());
        return ApiResponse.ok(summaries.stream().map(PositionMonthSummaryDto::from).toList());
    }
}
