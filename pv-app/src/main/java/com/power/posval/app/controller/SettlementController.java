package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.SettlementCellDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementCellRepository cellRepo;
    private final TransactionalExecutor txExecutor;

    public SettlementController(SettlementCellRepository cellRepo,
                                 TransactionalExecutor txExecutor) {
        this.cellRepo = cellRepo;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<SettlementCellDto>> findByPosition(
            @RequestParam String tenantId,
            @RequestParam String positionId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd) {
        var cells = txExecutor.execute(
                () -> cellRepo.findByPosition(tenantId, UUID.fromString(positionId),
                        Instant.parse(rangeStart), Instant.parse(rangeEnd)));
        return ApiResponse.ok(cells.stream().map(SettlementCellDto::from).toList());
    }
}
