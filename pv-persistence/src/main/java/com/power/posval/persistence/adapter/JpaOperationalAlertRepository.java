package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.persistence.entity.OperationalAlertEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA adapter for {@link OperationalAlertRepository}.
 *
 * <p>In-place state transitions ({@link #acknowledge} and {@link #resolve}) use JPQL
 * UPDATE statements to avoid reloading the full entity just for status changes. This
 * matches the port's contract comment (DA-OPS-01, S5.1).
 *
 * <p>{@link #countByStatus} uses a JPQL GROUP BY query to count per category in a
 * single round-trip (dashboard badge counts). The result map may omit categories with
 * zero alerts, as documented in the port interface.
 *
 * <p>Pattern #18 (Port + Adapter), #23 (BIGINT sequence, allocationSize=50), S6.1.
 */
public class JpaOperationalAlertRepository implements OperationalAlertRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaOperationalAlertRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(OperationalAlert alert) {
        emProvider.get().persist(OperationalAlertEntity.fromDomain(alert));
    }

    /**
     * OPEN alerts ordered CRITICAL first, then WARNING, then INFO, then by
     * {@code raisedAt} descending within the same severity. DA-OPS-01 dashboard.
     * Note: JPQL ORDER BY on a string severity column achieves natural alphabetical sort
     * (CRITICAL < INFO < WARNING). A CASE expression would be more precise but is not
     * supported uniformly across JPA providers. The index covers (tenant_id, status,
     * severity, raised_at) which supports both filter and sort efficiently.
     */
    @Override
    public List<OperationalAlert> findOpen(String tenantId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM OperationalAlertEntity e
                WHERE e.tenantId = :tenantId
                  AND e.status   = 'OPEN'
                ORDER BY CASE e.severity
                           WHEN 'CRITICAL' THEN 0
                           WHEN 'WARNING'  THEN 1
                           WHEN 'INFO'     THEN 2
                           ELSE 3
                         END ASC, e.raisedAt DESC
                """, OperationalAlertEntity.class)
            .setParameter("tenantId", tenantId)
            .getResultStream()
            .map(OperationalAlertEntity::toDomain)
            .toList();
    }

    @Override
    public List<OperationalAlert> findByCategory(String tenantId, AlertCategory category,
                                                  AlertStatus status) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM OperationalAlertEntity e
                WHERE e.tenantId = :tenantId
                  AND e.category = :category
                  AND e.status   = :status
                ORDER BY e.raisedAt DESC
                """, OperationalAlertEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("category", category.name())
            .setParameter("status", status.name())
            .getResultStream()
            .map(OperationalAlertEntity::toDomain)
            .toList();
    }

    @Override
    public List<OperationalAlert> findByDeliveryDay(String tenantId, LocalDate deliveryDay) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM OperationalAlertEntity e
                WHERE e.tenantId    = :tenantId
                  AND e.deliveryDay = :deliveryDay
                ORDER BY e.raisedAt DESC
                """, OperationalAlertEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("deliveryDay", deliveryDay)
            .getResultStream()
            .map(OperationalAlertEntity::toDomain)
            .toList();
    }

    /**
     * In-place acknowledgement — sets status, acknowledgedBy, acknowledgedAt without
     * reloading the full entity. DA-OPS-01, S5.1.
     */
    @Override
    public void acknowledge(String tenantId, UUID alertId, String acknowledgedBy,
                            Instant acknowledgedAt) {
        emProvider.get()
            .createQuery("""
                UPDATE OperationalAlertEntity e
                SET e.status         = 'ACKNOWLEDGED',
                    e.acknowledgedBy = :acknowledgedBy,
                    e.acknowledgedAt = :acknowledgedAt
                WHERE e.tenantId = :tenantId
                  AND e.alertId  = :alertId
                  AND e.status   = 'OPEN'
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("alertId", alertId)
            .setParameter("acknowledgedBy", acknowledgedBy)
            .setParameter("acknowledgedAt", acknowledgedAt)
            .executeUpdate();
    }

    /**
     * In-place resolution — sets status and resolvedAt without reloading the full entity.
     * DA-OPS-01, S5.1.
     */
    @Override
    public void resolve(String tenantId, UUID alertId, Instant resolvedAt) {
        emProvider.get()
            .createQuery("""
                UPDATE OperationalAlertEntity e
                SET e.status     = 'RESOLVED',
                    e.resolvedAt = :resolvedAt
                WHERE e.tenantId = :tenantId
                  AND e.alertId  = :alertId
                  AND e.status   IN ('OPEN', 'ACKNOWLEDGED')
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("alertId", alertId)
            .setParameter("resolvedAt", resolvedAt)
            .executeUpdate();
    }

    /**
     * Count alerts per category for a given status in a single GROUP BY query.
     * Dashboard badge counts (DA-OPS-01). Categories with zero alerts are omitted
     * from the returned map, as permitted by the port contract.
     */
    @Override
    public Map<AlertCategory, Long> countByStatus(String tenantId, AlertStatus status) {
        List<Object[]> rows = emProvider.get()
            .createQuery("""
                SELECT e.category, COUNT(e)
                FROM OperationalAlertEntity e
                WHERE e.tenantId = :tenantId
                  AND e.status   = :status
                GROUP BY e.category
                """, Object[].class)
            .setParameter("tenantId", tenantId)
            .setParameter("status", status.name())
            .getResultList();

        Map<AlertCategory, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            AlertCategory cat = AlertCategory.valueOf((String) row[0]);
            Long count = (Long) row[1];
            result.put(cat, count);
        }
        return result;
    }
}
