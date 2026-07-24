package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.repository.DependencyEdge;
import com.power.posval.domain.port.repository.DependencyIndex;
import com.power.posval.domain.service.PrunePolicy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.util.List;

/**
 * JPA adapter for DependencyIndex. §13.2, Pattern #18.
 * Edge upsert/prune/query for blast-radius optimization.
 */
public class JpaDependencyIndex implements DependencyIndex {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaDependencyIndex(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void upsert(DependencyEdge edge) {
        // Skeleton — upsert edge with active_leaves via native SQL UPSERT
        // ON CONFLICT (tenant_id, cell_id, input_series_key) DO UPDATE SET ...
    }

    @Override
    public List<DependencyEdge> findAffectedCells(String tenantId,
                                                    String inputSeriesKey,
                                                    DeliveryRange affectedRange,
                                                    String activeLeafFilter) {
        // Skeleton — query dependency_edge table
        return List.of();
    }

    @Override
    public void prune(String tenantId, PrunePolicy policy) {
        // Skeleton — set pruned_at based on policy type
    }
}
