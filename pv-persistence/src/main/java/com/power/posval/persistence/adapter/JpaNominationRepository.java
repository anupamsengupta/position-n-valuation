package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.port.repository.NominationRepository;
import com.power.posval.persistence.entity.NominationRecordEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for {@link NominationRepository}.
 *
 * <p>Nominations are ordered by {@code intervalStart} ascending on all multi-row queries
 * so callers receive a ready-to-use chronological list (S5.1, DA-VOL-03).
 *
 * <p>Pattern #18 (Port + Adapter), #23 (BIGINT sequence, allocationSize=50), S6.1.
 */
public class JpaNominationRepository implements NominationRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaNominationRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(NominationRecord record) {
        emProvider.get().persist(NominationRecordEntity.fromDomain(record));
    }

    /**
     * Batch persist using individual {@code persist} calls within the same EntityManager
     * flush cycle. For bulk inserts the caller should ensure a transaction is active so
     * Hibernate's first-level cache batches the SQL writes according to
     * {@code hibernate.jdbc.batch_size}. DA-VOL-03.
     */
    @Override
    public void saveAll(List<NominationRecord> records) {
        EntityManager em = emProvider.get();
        for (NominationRecord record : records) {
            em.persist(NominationRecordEntity.fromDomain(record));
        }
    }

    /**
     * Load all nominations for a balancing group on a delivery day, ordered by
     * {@code intervalStart} ascending. DA-VOL-03, S5.1.
     */
    @Override
    public List<NominationRecord> findByDeliveryDay(String tenantId, String balancingGroupId,
                                                     LocalDate deliveryDay) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM NominationRecordEntity e
                WHERE e.tenantId         = :tenantId
                  AND e.balancingGroupId = :bgId
                  AND e.deliveryDay      = :deliveryDay
                ORDER BY e.intervalStart ASC
                """, NominationRecordEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("bgId", balancingGroupId)
            .setParameter("deliveryDay", deliveryDay)
            .getResultStream()
            .map(NominationRecordEntity::toDomain)
            .toList();
    }

    /**
     * Load the record with the highest {@code nominationVersion} for a given interval.
     * "Latest version wins" semantics per DA-VOL-03. S5.1.
     */
    @Override
    public Optional<NominationRecord> findLatestByInterval(String tenantId,
                                                            String balancingGroupId,
                                                            Instant intervalStart) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM NominationRecordEntity e
                WHERE e.tenantId         = :tenantId
                  AND e.balancingGroupId = :bgId
                  AND e.intervalStart    = :intervalStart
                ORDER BY e.nominationVersion DESC
                """, NominationRecordEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("bgId", balancingGroupId)
            .setParameter("intervalStart", intervalStart)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .map(NominationRecordEntity::toDomain);
    }
}
