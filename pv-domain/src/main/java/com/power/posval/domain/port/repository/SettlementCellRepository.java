package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.SettlementCell;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port interface for settlement cell persistence (S5a).
 * Pattern #18, FR-070, FR-071.
 */
public interface SettlementCellRepository {

    /** Persist a settlement cell (bitemporal). */
    void save(SettlementCell cell);

    /**
     * Batch persist multiple settlement cells in a single flush.
     * Default implementation falls back to individual saves; JPA adapters
     * should override to use batched JDBC inserts.
     */
    default void saveAll(List<SettlementCell> cells) {
        cells.forEach(this::save);
    }

    /** Find current-knowledge settlement cells for a position within a range. */
    List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                         Instant rangeStart, Instant rangeEnd);

    /**
     * Delete settlement cells for a position whose intervalStart falls within [start, end).
     * Used by revaluation to replace stale cells with fresh computations.
     * @return number of cells deleted
     */
    default int deleteByPositionAndInterval(String tenantId, UUID positionId,
                                             Instant intervalStart, Instant intervalEnd) {
        throw new UnsupportedOperationException("deleteByPositionAndInterval not implemented");
    }

    /** Check if any settlement cells exist for a given position (idempotency check). */
    default boolean existsByPositionId(String tenantId, UUID positionId) {
        return !findByPosition(tenantId, positionId,
                Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).isEmpty();
    }
}
