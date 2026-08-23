package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.SettlementCellDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.service.SettlementQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private static final Logger log = LoggerFactory.getLogger(SettlementController.class);

    private final SettlementQueryService settlementService;
    private final TransactionalExecutor txExecutor;

    public SettlementController(SettlementQueryService settlementService,
                                 TransactionalExecutor txExecutor) {
        this.settlementService = settlementService;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<SettlementCellDto>> findByPosition(
            @RequestParam String tenantId,
            @RequestParam String positionId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd) {
        log.info("GET /api/settlements tenantId={} positionId={} range=[{} .. {}]",
            tenantId, positionId, rangeStart, rangeEnd);
        var cells = txExecutor.execute(
                () -> settlementService.findByPosition(tenantId, UUID.fromString(positionId),
                        Instant.parse(rangeStart), Instant.parse(rangeEnd)));
        log.info("GET /api/settlements => {} cells", cells.size());
        return ApiResponse.ok(cells.stream().map(SettlementCellDto::from).toList());
    }
}
