package com.power.posval.domain.service;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.port.service.RollupQueryService;

import java.time.Instant;
import java.util.List;

/**
 * Default implementation of {@link RollupQueryService}.
 * Delegates queries to {@link RollupRepository} and materialization to
 * {@link RollupMaterializationService}.
 */
public class DefaultRollupQueryService implements RollupQueryService {

    private final RollupRepository rollupRepo;
    private final RollupMaterializationService materializationService;

    public DefaultRollupQueryService(RollupRepository rollupRepo,
                                      RollupMaterializationService materializationService) {
        this.rollupRepo = rollupRepo;
        this.materializationService = materializationService;
    }

    @Override
    public List<RollupCell> findByRange(String tenantId, String deliveryPointId, String portfolioId,
                                          Instant rangeStart, Instant rangeEnd,
                                          TimeGranularity granularity) {
        return rollupRepo.findByRange(tenantId, deliveryPointId, portfolioId,
                rangeStart, rangeEnd, granularity);
    }

    @Override
    public void materialize(String tenantId, Instant rangeStart, Instant rangeEnd,
                              TimeGranularity granularity) {
        materializationService.materialize(tenantId, rangeStart, rangeEnd, granularity);
    }
}
