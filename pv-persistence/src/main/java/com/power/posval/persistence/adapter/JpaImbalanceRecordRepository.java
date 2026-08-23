package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.port.repository.ImbalanceRecordRepository;
import com.power.posval.persistence.entity.ImbalanceRecordEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * JPA adapter for {@link ImbalanceRecordRepository}.
 *
 * <p>Monthly range queries derive the UTC boundary of the month's first and last day
 * using the {@code delivery_day} denormalized column (a DATE in CET time) to avoid
 * timezone-sensitive range arithmetic in JPQL (DA-SET-04, S5.1, S7.1).
 *
 * <p>Pattern #18 (Port + Adapter), #23 (BIGINT sequence, allocationSize=50), S6.1.
 */
public class JpaImbalanceRecordRepository implements ImbalanceRecordRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaImbalanceRecordRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(ImbalanceRecord record) {
        emProvider.get().persist(ImbalanceRecordEntity.fromDomain(record));
    }

    @Override
    public void saveAll(List<ImbalanceRecord> records) {
        EntityManager em = emProvider.get();
        for (ImbalanceRecord record : records) {
            em.persist(ImbalanceRecordEntity.fromDomain(record));
        }
    }

    /**
     * Load all imbalance records for a balancing group on a delivery day,
     * ordered by {@code intervalStart} ascending. DA-SET-04, S5.1.
     */
    @Override
    public List<ImbalanceRecord> findByDeliveryDay(String tenantId, String balancingGroupId,
                                                    LocalDate deliveryDay) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM ImbalanceRecordEntity e
                WHERE e.tenantId         = :tenantId
                  AND e.balancingGroupId = :bgId
                  AND e.deliveryDay      = :deliveryDay
                ORDER BY e.intervalStart ASC
                """, ImbalanceRecordEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("bgId", balancingGroupId)
            .setParameter("deliveryDay", deliveryDay)
            .getResultStream()
            .map(ImbalanceRecordEntity::toDomain)
            .toList();
    }

    /**
     * Load all imbalance records for a balancing group within a calendar month.
     * Uses the denormalized {@code delivery_day} DATE column with a half-open month range
     * [{@code monthStart}, {@code monthEnd}) to avoid TIMESTAMPTZ arithmetic in JPQL.
     * DA-SET-04 monthly aggregation, S5.1.
     */
    @Override
    public List<ImbalanceRecord> findByMonth(String tenantId, String balancingGroupId,
                                              YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd   = month.atEndOfMonth().plusDays(1); // exclusive upper bound

        return emProvider.get()
            .createQuery("""
                SELECT e FROM ImbalanceRecordEntity e
                WHERE e.tenantId         = :tenantId
                  AND e.balancingGroupId = :bgId
                  AND e.deliveryDay >= :monthStart
                  AND e.deliveryDay <  :monthEnd
                ORDER BY e.intervalStart ASC
                """, ImbalanceRecordEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("bgId", balancingGroupId)
            .setParameter("monthStart", monthStart)
            .setParameter("monthEnd", monthEnd)
            .getResultStream()
            .map(ImbalanceRecordEntity::toDomain)
            .toList();
    }
}
