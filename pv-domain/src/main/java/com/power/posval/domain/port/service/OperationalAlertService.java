package com.power.posval.domain.port.service;

import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service port for operational alert lifecycle management (DA-OPS-01).
 *
 * <p>Alerts are raised by domain services (import orchestrator, nomination service,
 * imbalance settlement service) when anomalies or failures are detected. They are
 * acknowledged and resolved by operators via the dashboard.
 *
 * <p>Alert delivery in v1 is pull-based (UI polling only). Push notification via
 * email/webhook is deferred to OQ-DA-OPS-2.
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface OperationalAlertService {

    /**
     * Persist a new operational alert. Publishes {@code OperationalAlertRaised} via
     * the outbox in the same transaction.
     *
     * @param alert the alert to raise; must not be null; status must be {@code OPEN}
     */
    void raise(OperationalAlert alert);

    /**
     * Acknowledge an open alert on behalf of a user. Transitions status from
     * {@code OPEN} to {@code ACKNOWLEDGED}.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param alertId  UUID business key of the alert
     * @param user     identifier of the user acknowledging the alert
     * @throws com.power.posval.domain.exception.IllegalStateTransitionException if the
     *         alert is not in {@code OPEN} status
     */
    void acknowledgeAlert(String tenantId, UUID alertId, String user);

    /**
     * Resolve an alert. Transitions status from {@code OPEN} or {@code ACKNOWLEDGED}
     * to {@code RESOLVED}.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param alertId  UUID business key of the alert
     * @throws com.power.posval.domain.exception.IllegalStateTransitionException if the
     *         alert is already {@code RESOLVED}
     */
    void resolveAlert(String tenantId, UUID alertId);

    /**
     * Return all open alerts for a tenant, ordered by severity (CRITICAL first) then
     * by {@code raisedAt} descending. Used by the dashboard (DA-OPS-01).
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @return open alerts; empty list if none
     */
    List<OperationalAlert> getOpenAlerts(String tenantId);

    /**
     * Return a count of alerts per category for a given status.
     * Used by the dashboard alert badge counts.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @return map from category to count; categories with zero alerts may be omitted
     */
    Map<AlertCategory, Long> getAlertCounts(String tenantId);
}
