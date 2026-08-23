package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.ExchangeFeeSchedule;
import com.power.posval.domain.port.repository.ExchangeFeeScheduleRepository;
import com.power.posval.persistence.entity.ExchangeFeeScheduleEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.Optional;

/**
 * JPA adapter for {@link ExchangeFeeScheduleRepository}.
 *
 * <p>Effective-date lookup pattern:
 * {@code effectiveFrom <= :date AND (effectiveTo IS NULL OR effectiveTo > :date)}.
 * When multiple schedules overlap (should not happen if managed correctly), the one
 * with the latest {@code effectiveFrom} is returned ({@code ORDER BY effectiveFrom DESC LIMIT 1}).
 *
 * <p>The two-overload design from the port is implemented as two distinct JPQL queries —
 * one filtering on {@code memberTier IS NULL} (default tier) and one filtering on a
 * specific {@code memberTier} value. DA-SET-03, S5.1.
 *
 * <p>Pattern #18 (Port + Adapter), #23 (BIGINT sequence, allocationSize=50), S6.1.
 */
public class JpaExchangeFeeScheduleRepository implements ExchangeFeeScheduleRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaExchangeFeeScheduleRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Default-tier lookup: {@code memberTier IS NULL}. DA-SET-03.
     */
    @Override
    public Optional<ExchangeFeeSchedule> findEffective(String tenantId, String exchange,
                                                        String feeType, LocalDate deliveryDate) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM ExchangeFeeScheduleEntity e
                WHERE e.tenantId      = :tenantId
                  AND e.exchange      = :exchange
                  AND e.feeType       = :feeType
                  AND e.memberTier   IS NULL
                  AND e.effectiveFrom <= :date
                  AND (e.effectiveTo  IS NULL OR e.effectiveTo > :date)
                ORDER BY e.effectiveFrom DESC
                """, ExchangeFeeScheduleEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("exchange", exchange)
            .setParameter("feeType", feeType)
            .setParameter("date", deliveryDate)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .map(ExchangeFeeScheduleEntity::toDomain);
    }

    /**
     * Tier-specific lookup. When {@code memberTier} is {@code null} this overload
     * delegates to the default-tier lookup for consistent behaviour with the port contract.
     * DA-SET-03.
     */
    @Override
    public Optional<ExchangeFeeSchedule> findEffective(String tenantId, String exchange,
                                                        String feeType, String memberTier,
                                                        LocalDate deliveryDate) {
        if (memberTier == null) {
            return findEffective(tenantId, exchange, feeType, deliveryDate);
        }
        return emProvider.get()
            .createQuery("""
                SELECT e FROM ExchangeFeeScheduleEntity e
                WHERE e.tenantId      = :tenantId
                  AND e.exchange      = :exchange
                  AND e.feeType       = :feeType
                  AND e.memberTier    = :memberTier
                  AND e.effectiveFrom <= :date
                  AND (e.effectiveTo  IS NULL OR e.effectiveTo > :date)
                ORDER BY e.effectiveFrom DESC
                """, ExchangeFeeScheduleEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("exchange", exchange)
            .setParameter("feeType", feeType)
            .setParameter("memberTier", memberTier)
            .setParameter("date", deliveryDate)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .map(ExchangeFeeScheduleEntity::toDomain);
    }

    @Override
    public void save(ExchangeFeeSchedule schedule) {
        emProvider.get().persist(ExchangeFeeScheduleEntity.fromDomain(schedule));
    }
}
