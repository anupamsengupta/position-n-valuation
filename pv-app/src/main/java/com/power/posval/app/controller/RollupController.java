package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.RollupCellDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.service.RollupQueryService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/rollups")
public class RollupController {

    private final RollupQueryService rollupService;
    private final TransactionalExecutor txExecutor;

    public RollupController(RollupQueryService rollupService,
                             TransactionalExecutor txExecutor) {
        this.rollupService = rollupService;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<RollupCellDto>> findByRange(
            @RequestParam String tenantId,
            @RequestParam String deliveryPointId,
            @RequestParam String portfolioId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd,
            @RequestParam(defaultValue = "MONTHLY") String granularity) {
        var cells = txExecutor.execute(
                () -> rollupService.findByRange(tenantId, deliveryPointId, portfolioId,
                        Instant.parse(rangeStart), Instant.parse(rangeEnd),
                        TimeGranularity.valueOf(granularity)));
        return ApiResponse.ok(cells.stream().map(RollupCellDto::from).toList());
    }

    @PostMapping("/materialize")
    public ApiResponse<String> materialize(
            @RequestParam String tenantId,
            @RequestParam String rangeStart,
            @RequestParam String rangeEnd,
            @RequestParam(defaultValue = "MONTHLY") String granularity) {
        txExecutor.run(() -> rollupService.materialize(tenantId,
                Instant.parse(rangeStart), Instant.parse(rangeEnd),
                TimeGranularity.valueOf(granularity)));
        return ApiResponse.ok("Rollup materialization completed");
    }
}
