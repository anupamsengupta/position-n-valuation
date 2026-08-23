package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;
import com.power.posval.domain.port.repository.BlockDefinitionRepository;
import com.power.posval.persistence.entity.BlockDefinitionEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for {@link BlockDefinitionRepository}.
 *
 * <p>System-level (tenant-independent) — no {@code tenantId} in any query.
 * Block definitions are exchange-published reference data, not per-tenant configuration
 * (S5.1 note, DA-VOL-02).
 *
 * <p>Effective-date pattern:
 * {@code effectiveFrom <= :date AND (effectiveTo IS NULL OR effectiveTo > :date)}.
 *
 * <p>Zone-specific lookup takes precedence in application logic; if a zone-specific entry
 * is not found, callers may retry with {@code biddingZone = null} to look up the
 * zone-independent definition. The two resolution steps are explicit at the call site —
 * this adapter does not silently fall back (keeps the adapter simple and predictable).
 *
 * <p>Pattern #18 (Port + Adapter), S6.1.
 */
public class JpaBlockDefinitionRepository implements BlockDefinitionRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaBlockDefinitionRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Find the effective block definition for a specific exchange, block type, bidding zone,
     * and delivery date. When {@code biddingZone} is {@code null}, matches entries where
     * {@code biddingZone IS NULL} (zone-independent definitions). DA-VOL-02, S5.1.
     */
    @Override
    public Optional<BlockDefinition> findEffective(String exchange, BlockType blockType,
                                                    String biddingZone, LocalDate deliveryDate) {
        if (biddingZone == null) {
            return emProvider.get()
                .createQuery("""
                    SELECT e FROM BlockDefinitionEntity e
                    WHERE e.exchange      = :exchange
                      AND e.blockType    = :blockType
                      AND e.biddingZone IS NULL
                      AND e.effectiveFrom <= :date
                      AND (e.effectiveTo  IS NULL OR e.effectiveTo > :date)
                    ORDER BY e.effectiveFrom DESC
                    """, BlockDefinitionEntity.class)
                .setParameter("exchange", exchange)
                .setParameter("blockType", blockType.name())
                .setParameter("date", deliveryDate)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(BlockDefinitionEntity::toDomain);
        }

        return emProvider.get()
            .createQuery("""
                SELECT e FROM BlockDefinitionEntity e
                WHERE e.exchange      = :exchange
                  AND e.blockType    = :blockType
                  AND e.biddingZone  = :biddingZone
                  AND e.effectiveFrom <= :date
                  AND (e.effectiveTo  IS NULL OR e.effectiveTo > :date)
                ORDER BY e.effectiveFrom DESC
                """, BlockDefinitionEntity.class)
            .setParameter("exchange", exchange)
            .setParameter("blockType", blockType.name())
            .setParameter("biddingZone", biddingZone)
            .setParameter("date", deliveryDate)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .map(BlockDefinitionEntity::toDomain);
    }

    /**
     * Load all effective block definitions for an exchange on a delivery date.
     * Used by the decomposition engine to bulk-load reference data once per import session.
     * DA-VOL-02, S5.1.
     */
    @Override
    public List<BlockDefinition> findAllEffective(String exchange, LocalDate deliveryDate) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM BlockDefinitionEntity e
                WHERE e.exchange      = :exchange
                  AND e.effectiveFrom <= :date
                  AND (e.effectiveTo  IS NULL OR e.effectiveTo > :date)
                ORDER BY e.blockType ASC, e.biddingZone ASC
                """, BlockDefinitionEntity.class)
            .setParameter("exchange", exchange)
            .setParameter("date", deliveryDate)
            .getResultStream()
            .map(BlockDefinitionEntity::toDomain)
            .toList();
    }
}
