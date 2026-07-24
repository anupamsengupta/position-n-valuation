package com.power.posval.persistence.adapter;

import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.persistence.entity.SettlementCellEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA adapter for SettlementCellRepository. §11.1, Pattern #18.
 * Bitemporal cell persistence.
 */
public class JpaSettlementCellRepository implements SettlementCellRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaSettlementCellRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(Object cell) {
        if (cell instanceof SettlementCellEntity entity) {
            emProvider.get().persist(entity);
        } else {
            throw new IllegalArgumentException(
                "Expected SettlementCellEntity, got: " + cell.getClass().getName());
        }
    }

    @Override
    public List<Object> findByPosition(String tenantId, UUID positionId,
                                        Instant rangeStart, Instant rangeEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM SettlementCellEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.positionId = :positionId
                  AND e.intervalStart < :rangeEnd
                  AND e.intervalEnd > :rangeStart
                  AND e.knownTo IS NULL
                ORDER BY e.intervalStart
                """, SettlementCellEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("positionId", positionId)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd)
            .getResultStream()
            .map(e -> (Object) e)
            .toList();
    }
}
