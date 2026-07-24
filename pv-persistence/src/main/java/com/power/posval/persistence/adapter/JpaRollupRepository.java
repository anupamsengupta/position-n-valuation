package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;

/**
 * JPA adapter for RollupRepository. §13.1, Pattern #18.
 * Rollup reads + refresh.
 */
public class JpaRollupRepository implements RollupRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaRollupRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public List<RollupCell> findByRange(String tenantId,
                                         String deliveryPointId,
                                         String portfolioId,
                                         Instant rangeStart,
                                         Instant rangeEnd,
                                         TimeGranularity granularity) {
        // Skeleton — rollup table queries depend on materialized view schema
        return List.of();
    }

    @Override
    public void refresh(String tenantId,
                         Instant rangeStart,
                         Instant rangeEnd,
                         TimeGranularity granularity) {
        // Skeleton — triggers materialized view refresh
    }
}
