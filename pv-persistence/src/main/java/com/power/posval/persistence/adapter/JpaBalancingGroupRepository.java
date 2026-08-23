package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.BalancingGroup;
import com.power.posval.domain.port.repository.BalancingGroupRepository;
import com.power.posval.persistence.entity.BalancingGroupEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for {@link BalancingGroupRepository}.
 *
 * <p>Tenant-scoped — all queries filter on {@code tenantId} (D-14, Pattern #32).
 * The UNIQUE constraint on {@code (tenant_id, bg_code)} ensures one canonical entry
 * per code per tenant (S7.1, DA-VOL-03).
 *
 * <p>{@link #findActive} returns groups where {@code activeTo IS NULL OR activeTo > today()}.
 * "Today" is evaluated at query time via JPQL's {@code CURRENT_DATE} function to avoid
 * clock drift between application and database.
 *
 * <p>Pattern #18 (Port + Adapter), #23 (BIGINT sequence, allocationSize=50), S6.1.
 */
public class JpaBalancingGroupRepository implements BalancingGroupRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaBalancingGroupRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public Optional<BalancingGroup> findById(String tenantId, UUID bgId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM BalancingGroupEntity e
                WHERE e.tenantId = :tenantId
                  AND e.bgId    = :bgId
                """, BalancingGroupEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("bgId", bgId)
            .getResultStream()
            .findFirst()
            .map(BalancingGroupEntity::toDomain);
    }

    @Override
    public Optional<BalancingGroup> findByCode(String tenantId, String bgCode) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM BalancingGroupEntity e
                WHERE e.tenantId = :tenantId
                  AND e.bgCode   = :bgCode
                """, BalancingGroupEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("bgCode", bgCode)
            .getResultStream()
            .findFirst()
            .map(BalancingGroupEntity::toDomain);
    }

    /**
     * Load all currently active balancing groups for a tenant.
     * "Active" = {@code activeTo IS NULL OR activeTo > CURRENT_DATE}.
     * Ordered by {@code bgCode} for deterministic results. DA-VOL-03, S5.1.
     */
    @Override
    public List<BalancingGroup> findActive(String tenantId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM BalancingGroupEntity e
                WHERE e.tenantId = :tenantId
                  AND (e.activeTo IS NULL OR e.activeTo > CURRENT_DATE)
                ORDER BY e.bgCode ASC
                """, BalancingGroupEntity.class)
            .setParameter("tenantId", tenantId)
            .getResultStream()
            .map(BalancingGroupEntity::toDomain)
            .toList();
    }
}
