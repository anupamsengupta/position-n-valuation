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

    /**
     * Batch persist multiple ledger entries in a single flush.
     * Default implementation falls back to individual saves; JPA adapters
     * should override to use {@code EntityManager.persist()} in a single
     * transaction with batched JDBC inserts.
     */
    default void saveAll(List<PositionLedgerEntry> entries) {
        entries.forEach(this::save);
    }

    Optional<PositionLedgerEntry> findById(UUID entryId);

    /** Current-knowledge entries for a trade-leg across all delivery months. */
    List<PositionLedgerEntry> findCurrentByTradeLeg(String tenantId, String tradeId, String tradeLegId);

    /** Bitemporal as-of reconstruction (FR-007). Grain = trade-leg × delivery-month. */
    List<PositionLedgerEntry> findAsOf(String tenantId, String tradeId, String tradeLegId,
                                       Instant businessDate, Instant knowledgeDate);

    /** All current-knowledge entries within a delivery range for a tenant (portfolio-wide). */
    List<PositionLedgerEntry> findAllByDeliveryRange(String tenantId,
                                                      Instant deliveryStart,
                                                      Instant deliveryEnd);

    /** Current-knowledge entries for a specific trade-leg within a delivery range. */
    List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String tenantId,
                                                              String tradeId,
                                                              String tradeLegId,
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
