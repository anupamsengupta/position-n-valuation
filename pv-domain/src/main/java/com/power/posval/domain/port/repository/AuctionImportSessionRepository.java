package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for {@link AuctionImportSession} persistence.
 *
 * <p>The natural business key {@code (tenantId, exchange, biddingZone, deliveryDay)} carries
 * a UNIQUE constraint on the underlying table, providing the idempotency guard for
 * re-import attempts (DA-VOL-01 Scenario 5).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface AuctionImportSessionRepository {

    /**
     * Persist a new import session.
     *
     * @param session the session to persist; must not be null
     */
    void save(AuctionImportSession session);

    /**
     * Load a single session by its UUID business key within a tenant.
     *
     * @param tenantId  tenant identifier (D-14, Pattern #32)
     * @param sessionId UUID business key
     * @return the session if found
     */
    Optional<AuctionImportSession> findBySessionId(String tenantId, UUID sessionId);

    /**
     * Load the session for a specific delivery day / exchange / bidding-zone tuple.
     * Returns the most recent session if more than one exists (edge case: re-runs after failure).
     * Used as the idempotency check in {@code AuctionImportOrchestrator} (DA-VOL-01 Scenario 5).
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param exchange    exchange code, e.g. {@code "EPEX_SPOT"}
     * @param biddingZone bidding zone code, e.g. {@code "DE_LU"}
     * @param deliveryDay CET-interpreted delivery day
     * @return the import session if one exists for this tuple
     */
    Optional<AuctionImportSession> findByDeliveryDay(String tenantId, String exchange,
                                                      String biddingZone, LocalDate deliveryDay);

    /**
     * Update the status and optional completion timestamp of an existing session in place.
     * Used by the import orchestrator to advance the session state machine without reloading
     * the full object from the database.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param sessionId   UUID business key of the session
     * @param status      the new status to set
     * @param completedAt completion timestamp; may be {@code null} for intermediate states
     */
    void updateStatus(String tenantId, UUID sessionId, AuctionImportStatus status,
                      Instant completedAt);

    /**
     * Load all import sessions for a tenant, exchange, and bidding zone, ordered by
     * {@code importTimestamp} descending (most recent first).
     * Used by {@code AuctionImportService#getImportHistory} (S5.2).
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param exchange    exchange code, e.g. {@code "EPEX_SPOT"}
     * @param biddingZone bidding zone code, e.g. {@code "DE_LU"}
     * @return all sessions for the exchange and bidding zone; empty list if none
     */
    List<AuctionImportSession> findByExchangeAndBiddingZone(String tenantId, String exchange,
                                                             String biddingZone);
}
