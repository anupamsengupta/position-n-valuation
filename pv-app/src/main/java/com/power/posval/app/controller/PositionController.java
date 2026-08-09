package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.PositionLedgerEntryDto;
import com.power.posval.app.dto.PositionMonthSummaryDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.service.PositionQueryService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

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
        var entries = txExecutor.execute(
                () -> positionService.findCurrent(tenantId, tradeId, tradeLegId));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/as-of")
    public ApiResponse<List<PositionLedgerEntryDto>> findAsOf(
            @RequestParam String tenantId,
            @RequestParam String tradeId,
            @RequestParam String tradeLegId,
            @RequestParam String businessDate,
            @RequestParam String knowledgeDate) {
        var entries = txExecutor.execute(
                () -> positionService.findAsOf(tenantId, tradeId, tradeLegId,
                        Instant.parse(businessDate), Instant.parse(knowledgeDate)));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/by-range")
    public ApiResponse<List<PositionLedgerEntryDto>> findByDeliveryRange(
            @RequestParam String tenantId,
            @RequestParam String deliveryStart,
            @RequestParam String deliveryEnd) {
        var entries = txExecutor.execute(
                () -> positionService.findByDeliveryRange(tenantId,
                        Instant.parse(deliveryStart), Instant.parse(deliveryEnd)));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/summary")
    public ApiResponse<List<PositionMonthSummaryDto>> monthlySummary(
            @RequestParam String tenantId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd,
            @RequestParam(required = false) String positionId) {
        var summaries = txExecutor.execute(() -> {
            if (positionId != null) {
                return positionService.monthlySummaryByPosition(tenantId,
                        UUID.fromString(positionId),
                        Instant.parse(rangeStart), Instant.parse(rangeEnd));
            }
            return positionService.monthlySummary(tenantId,
                    Instant.parse(rangeStart), Instant.parse(rangeEnd));
        });
        return ApiResponse.ok(summaries.stream().map(PositionMonthSummaryDto::from).toList());
    }
}
