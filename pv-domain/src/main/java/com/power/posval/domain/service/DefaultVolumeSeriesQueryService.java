package com.power.posval.domain.service;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.service.VolumeSeriesQueryService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link VolumeSeriesQueryService}.
 * Delegates to repository ports.
 */
public class DefaultVolumeSeriesQueryService implements VolumeSeriesQueryService {

    private final VolumeSeriesRepository repo;

    public DefaultVolumeSeriesQueryService(VolumeSeriesRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<VolumeSeries> findByTenant(String tenantId) {
        return repo.findByTenantId(tenantId);
    }

    @Override
    public Optional<VolumeSeries> findById(UUID id) {
        return repo.findById(id);
    }
}
