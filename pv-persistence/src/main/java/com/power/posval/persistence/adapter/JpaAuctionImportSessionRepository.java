package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;
import com.power.posval.domain.port.repository.AuctionImportSessionRepository;
import com.power.posval.persistence.entity.AuctionImportSessionEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for {@link AuctionImportSessionRepository}.
 *
 * <p>The UNIQUE constraint on {@code (tenant_id, exchange, bidding_zone, delivery_day)}
 * is the database-level idempotency guard for re-import attempts (DA-VOL-01 Scenario 5).
 * The {@link #findByDeliveryDay} method provides the application-level check before
 * the import orchestrator proceeds.
 *
 * <p>Pattern #18 (Port + Adapter), #23 (BIGINT sequence, allocationSize=50), S6.1.
 */
public class JpaAuctionImportSessionRepository implements AuctionImportSessionRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaAuctionImportSessionRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(AuctionImportSession session) {
        emProvider.get().persist(AuctionImportSessionEntity.fromDomain(session));
    }

    @Override
    public Optional<AuctionImportSession> findBySessionId(String tenantId, UUID sessionId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM AuctionImportSessionEntity e
                WHERE e.tenantId  = :tenantId
                  AND e.sessionId = :sessionId
                """, AuctionImportSessionEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("sessionId", sessionId)
            .getResultStream()
            .findFirst()
            .map(AuctionImportSessionEntity::toDomain);
    }

    /**
     * Idempotency check: returns the most recent session for the 4-tuple
     * {@code (tenantId, exchange, biddingZone, deliveryDay)}.
     * The UNIQUE constraint on the table means at most one row should ever match;
     * the ORDER BY / LIMIT 1 is a defensive guard against edge-case double-inserts
     * that the DB constraint will reject anyway.
     * DA-VOL-01 Scenario 5, S5.1.
     */
    @Override
    public Optional<AuctionImportSession> findByDeliveryDay(String tenantId, String exchange,
                                                             String biddingZone,
                                                             LocalDate deliveryDay) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM AuctionImportSessionEntity e
                WHERE e.tenantId    = :tenantId
                  AND e.exchange    = :exchange
                  AND e.biddingZone = :biddingZone
                  AND e.deliveryDay = :deliveryDay
                ORDER BY e.createdAt DESC
                """, AuctionImportSessionEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("exchange", exchange)
            .setParameter("biddingZone", biddingZone)
            .setParameter("deliveryDay", deliveryDay)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .map(AuctionImportSessionEntity::toDomain);
    }

    /**
     * In-place status update via JPQL UPDATE to avoid reloading the full entity.
     * Only {@code status} and {@code completed_at} are modified; all other fields
     * remain unchanged.
     * S5.1.
     */
    @Override
    public void updateStatus(String tenantId, UUID sessionId, AuctionImportStatus status,
                             Instant completedAt) {
        emProvider.get()
            .createQuery("""
                UPDATE AuctionImportSessionEntity e
                SET e.status = :status, e.completedAt = :completedAt
                WHERE e.tenantId  = :tenantId
                  AND e.sessionId = :sessionId
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("sessionId", sessionId)
            .setParameter("status", status.name())
            .setParameter("completedAt", completedAt)
            .executeUpdate();
    }

    /**
     * Load all sessions for a tenant, exchange, and bidding zone ordered by
     * {@code importTimestamp} descending (most recent first).
     * Used by {@code AuctionImportService#getImportHistory}. S5.2.
     */
    @Override
    public List<AuctionImportSession> findByExchangeAndBiddingZone(String tenantId,
                                                                     String exchange,
                                                                     String biddingZone) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM AuctionImportSessionEntity e
                WHERE e.tenantId    = :tenantId
                  AND e.exchange    = :exchange
                  AND e.biddingZone = :biddingZone
                ORDER BY e.importTimestamp DESC
                """, AuctionImportSessionEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("exchange", exchange)
            .setParameter("biddingZone", biddingZone)
            .getResultStream()
            .map(AuctionImportSessionEntity::toDomain)
            .toList();
    }
}
