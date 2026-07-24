package com.power.posval.domain.port.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port interface for settlement cell persistence (S5a).
 * Pattern #18, FR-070, FR-071.
 */
public interface SettlementCellRepository {

    /** Persist a settlement cell (bitemporal). */
    void save(Object cell);

    /** Find settlement cells for a position. */
    List<Object> findByPosition(String tenantId, UUID positionId,
                                 Instant rangeStart, Instant rangeEnd);
}
