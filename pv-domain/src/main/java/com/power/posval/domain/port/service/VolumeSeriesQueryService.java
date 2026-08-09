package com.power.posval.domain.port.service;

import com.power.posval.domain.model.VolumeSeries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for volume series queries.
 * Decouples controllers from repository ports.
 */
public interface VolumeSeriesQueryService {

    List<VolumeSeries> findByTenant(String tenantId);

    Optional<VolumeSeries> findById(UUID id);
}
