package com.power.posval.domain.service.da;

import com.power.posval.domain.event.OperationalAlertRaised;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.domain.port.service.OperationalAlertService;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link OperationalAlertService}.
 *
 * <p>Delegates persistence to {@link OperationalAlertRepository} and publishes
 * {@link OperationalAlertRaised} via the outbox in the same transaction as the save,
 * so the event and the alert row are committed atomically (S8.5, Pattern #24).
 *
 * <p>Lifecycle mutations (acknowledge, resolve) use the repository's in-place update
 * methods rather than a full reload, to avoid a stale-object race in high-throughput
 * alert pipelines (DA-OPS-01).
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, DA-OPS-01.
 */
public class DefaultOperationalAlertService implements OperationalAlertService {

    private final OperationalAlertRepository alertRepository;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultOperationalAlertService(OperationalAlertRepository alertRepository,
                                          DomainEventPublisher eventPublisher) {
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository");
        this.eventPublisher  = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * Persist a new operational alert and publish {@link OperationalAlertRaised}.
     *
     * <p>The caller is responsible for ensuring the alert status is {@code OPEN}
     * before calling this method. Both the repository write and the outbox row
     * must be committed in the same {@code UnitOfWork.execute()} scope.
     *
     * <p>DA-OPS-01, Pattern #24.
     *
     * @param alert the alert to raise; must not be null; status must be OPEN
     */
    @Override
    public void raise(OperationalAlert alert) {
        Objects.requireNonNull(alert, "alert");
        alertRepository.save(alert);
        eventPublisher.publish(new OperationalAlertRaised(
            alert.tenantId(),
            alert.alertId(),
            alert.category(),
            alert.severity(),
            alert.message(),
            Instant.now()
        ));
    }

    /**
     * Acknowledge an open alert. Delegates the in-place status update to the repository.
     * The state-machine validation (OPEN -> ACKNOWLEDGED) is enforced by the JPA adapter
     * via a conditional UPDATE; if the alert is not OPEN, the adapter returns zero rows
     * and the transition effectively no-ops at the persistence layer.
     *
     * <p>DA-OPS-01.
     */
    @Override
    public void acknowledgeAlert(String tenantId, UUID alertId, String user) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(alertId,  "alertId");
        Objects.requireNonNull(user,     "user");
        alertRepository.acknowledge(tenantId, alertId, user, Instant.now());
    }

    /**
     * Resolve an alert. Delegates the in-place status update to the repository.
     *
     * <p>DA-OPS-01.
     */
    @Override
    public void resolveAlert(String tenantId, UUID alertId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(alertId,  "alertId");
        alertRepository.resolve(tenantId, alertId, Instant.now());
    }

    /**
     * Return all OPEN alerts for a tenant, ordered by severity (CRITICAL first) then
     * by {@code raisedAt} descending. Delegates ordering to the repository implementation.
     *
     * <p>DA-OPS-01.
     */
    @Override
    public List<OperationalAlert> getOpenAlerts(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return alertRepository.findOpen(tenantId);
    }

    /**
     * Return a count of alerts per category for status {@code OPEN}.
     * Used by the dashboard alert badge counts (DA-OPS-01).
     */
    @Override
    public Map<AlertCategory, Long> getAlertCounts(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return alertRepository.countByStatus(tenantId, AlertStatus.OPEN);
    }
}
