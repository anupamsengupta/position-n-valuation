package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.service.PrunePolicy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
     * Find distinct position IDs affected by an input series change within a time range.
     * Joins dependency_edge with settlement_cell to resolve cell_id → position_id.
     * FR-103: uses S8 blast-radius optimization instead of brute-force delivery-range scan.
     *
     * @param tenantId       tenant identifier
     * @param inputSeriesKey the market data series that changed (e.g., "EPEX_DA15")
     * @param rangeStart     affected range start (inclusive)
     * @param rangeEnd       affected range end (exclusive)
     * @return distinct position UUIDs whose settlement cells depend on the input series
     */
    default List<UUID> findAffectedPositionIds(String tenantId,
                                                String inputSeriesKey,
                                                Instant rangeStart,
                                                Instant rangeEnd) {
        throw new UnsupportedOperationException("findAffectedPositionIds not implemented");
    }

    /**
     * Prune edges that are no longer relevant.
     * FR-104: forward-curve edges drop on settlement handover;
     *         settlement-input edges persist until delivery month leaves hot store.
     */
    void prune(String tenantId, PrunePolicy policy);
}
