package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/volume-series")
public class VolumeSeriesController {

    private final VolumeSeriesRepository seriesRepo;
    private final TransactionalExecutor txExecutor;

    public VolumeSeriesController(VolumeSeriesRepository seriesRepo,
                                    TransactionalExecutor txExecutor) {
        this.seriesRepo = seriesRepo;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<VolumeSeries>> findByTenant(@RequestParam String tenantId) {
        var series = txExecutor.execute(() -> seriesRepo.findByTenantId(tenantId));
        return ApiResponse.ok(series);
    }

    @GetMapping("/{id}")
    public ApiResponse<VolumeSeries> findById(@PathVariable String id) {
        var series = txExecutor.execute(() -> seriesRepo.findById(UUID.fromString(id)));
        return series.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Volume series not found"));
    }
}
