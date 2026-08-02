package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.PositionLedgerEntryDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final PositionLedgerRepository ledgerRepo;
    private final TransactionalExecutor txExecutor;

    public PositionController(PositionLedgerRepository ledgerRepo,
                               TransactionalExecutor txExecutor) {
        this.ledgerRepo = ledgerRepo;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<PositionLedgerEntryDto>> findCurrent(
            @RequestParam String tenantId,
            @RequestParam String tradeId,
            @RequestParam String tradeLegId) {
        var entries = txExecutor.execute(
                () -> ledgerRepo.findCurrentByTradeLeg(tenantId, tradeId, tradeLegId));
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
                () -> ledgerRepo.findAsOf(tenantId, tradeId, tradeLegId,
                        Instant.parse(businessDate), Instant.parse(knowledgeDate)));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @GetMapping("/by-range")
    public ApiResponse<List<PositionLedgerEntryDto>> findByDeliveryRange(
            @RequestParam String tenantId,
            @RequestParam String deliveryStart,
            @RequestParam String deliveryEnd) {
        var entries = txExecutor.execute(
                () -> ledgerRepo.findAllByDeliveryRange(tenantId,
                        Instant.parse(deliveryStart), Instant.parse(deliveryEnd)));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }
}
