package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.service.VolumeSeriesQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/volume-series")
public class VolumeSeriesController {

    private static final Logger log = LoggerFactory.getLogger(VolumeSeriesController.class);

    private final VolumeSeriesQueryService volumeSeriesService;
    private final TransactionalExecutor txExecutor;

    public VolumeSeriesController(VolumeSeriesQueryService volumeSeriesService,
                                    TransactionalExecutor txExecutor) {
        this.volumeSeriesService = volumeSeriesService;
        this.txExecutor = txExecutor;
    }

    @GetMapping
    public ApiResponse<List<VolumeSeries>> findByTenant(@RequestParam String tenantId) {
        log.info("GET /api/volume-series tenantId={}", tenantId);
        var series = txExecutor.execute(() -> volumeSeriesService.findByTenant(tenantId));
        log.info("GET /api/volume-series => {} series", series.size());
        return ApiResponse.ok(series);
    }

    @GetMapping("/{id}")
    public ApiResponse<VolumeSeries> findById(@PathVariable String id) {
        log.info("GET /api/volume-series/{}", id);
        var series = txExecutor.execute(() -> volumeSeriesService.findById(UUID.fromString(id)));
        log.info("GET /api/volume-series/{} => found={}", id, series.isPresent());
        return series.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Volume series not found"));
    }
}
