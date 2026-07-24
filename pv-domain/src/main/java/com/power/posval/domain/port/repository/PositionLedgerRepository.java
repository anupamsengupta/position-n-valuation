package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.PositionLedgerEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for position ledger persistence.
 * FR-007: as-of reconstruction via bitemporal predicate.
 * FR-030: grain = trade-leg × delivery-month block.
 * Pattern #18, S1.
 */
public interface PositionLedgerRepository {

    /** Persist a new ledger entry (append-only). FR-006: known_from set to processing time. */
    void save(PositionLedgerEntry entry);

    Optional<PositionLedgerEntry> findById(UUID entryId);

    /** Current-knowledge entries for a trade-leg across all delivery months. */
    List<PositionLedgerEntry> findCurrentByTradeLeg(String tenantId, String tradeId, String tradeLegId);

    /** Bitemporal as-of reconstruction (FR-007). */
    List<PositionLedgerEntry> findAsOf(String tenantId, String tradeId,
                                       Instant businessDate, Instant knowledgeDate);

    /** All current-knowledge entries within a delivery range for a tenant. */
    List<PositionLedgerEntry> findByDeliveryRange(String tenantId,
                                                   Instant deliveryStart,
                                                   Instant deliveryEnd);

    /**
     * Supersede existing entries by closing known_to, then saving new versions.
     * FR-037: knowledge-time supersession.
     * @param entriesToClose entries whose known_to will be set to now()
     * @param newEntries     replacement entries with known_from = now()
     */
    void supersede(List<PositionLedgerEntry> entriesToClose,
                   List<PositionLedgerEntry> newEntries);
}
