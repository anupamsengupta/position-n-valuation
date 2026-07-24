package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.service.PrunePolicy;

import java.util.List;

/**
 * Port interface for the dependency index (S8).
 * FR-102: reverse-dependency edges for incremental revaluation.
 * FR-103: active_leaves filtering for blast-radius optimization.
 * FR-104: lifecycle pruning.
 */
public interface DependencyIndex {

    /** Upsert a dependency edge: cell depends on input series. */
    void upsert(DependencyEdge edge);

    /**
     * Find all cells affected by an input series change within a reference range.
     * FR-102: index lookup, never a valuation-store scan.
     * @param activeLeafFilter if non-null, return only edges where this leaf is active (FR-103)
     */
    List<DependencyEdge> findAffectedCells(String tenantId,
                                            String inputSeriesKey,
                                            DeliveryRange affectedRange,
                                            String activeLeafFilter);

    /**
     * Prune edges that are no longer relevant.
     * FR-104: forward-curve edges drop on settlement handover;
     *         settlement-input edges persist until delivery month leaves hot store.
     */
    void prune(String tenantId, PrunePolicy policy);
}
