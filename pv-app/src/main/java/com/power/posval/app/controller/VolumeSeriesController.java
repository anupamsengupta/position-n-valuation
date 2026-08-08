package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.service.VolumeSeriesQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/volume-series")
public class VolumeSeriesController {

    private final VolumeSeriesQueryService volumeSeriesService;
    private final TransactionalExecutor txExecutor;

    public VolumeSeriesController(VolumeSeriesQueryService volumeSeriesService,
                                    TransactionalExecutor txExecutor) {
        this.volumeSeriesService = volumeSeriesService;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<VolumeSeries>> findByTenant(@RequestParam String tenantId) {
        var series = txExecutor.execute(() -> volumeSeriesService.findByTenant(tenantId));
        return ApiResponse.ok(series);
    }

    @GetMapping("/{id}")
    public ApiResponse<VolumeSeries> findById(@PathVariable String id) {
        var series = txExecutor.execute(() -> volumeSeriesService.findById(UUID.fromString(id)));
        return series.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Volume series not found"));
    }
}
