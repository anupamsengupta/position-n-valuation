package com.power.posval.domain.port.service;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupCell;

import java.time.Instant;
import java.util.List;

/**
 * Service interface for rollup queries and materialization trigger.
 * Decouples controllers from repository ports.
 */
public interface RollupQueryService {

    List<RollupCell> findByRange(String tenantId, String deliveryPointId, String portfolioId,
                                  Instant rangeStart, Instant rangeEnd, TimeGranularity granularity);

    void materialize(String tenantId, Instant rangeStart, Instant rangeEnd,
                      TimeGranularity granularity);
}
