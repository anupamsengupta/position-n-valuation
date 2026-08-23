package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port interface for {@link OperationalAlert} persistence.
 *
 * <p>Alerts support a three-state lifecycle: OPEN → ACKNOWLEDGED → RESOLVED.
 * The {@link #acknowledge} and {@link #resolve} methods perform in-place state
 * transitions to avoid full re-loads for status changes (DA-OPS-01).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface OperationalAlertRepository {

    /**
     * Persist a new operational alert.
     *
     * @param alert the alert to persist; must not be null
     */
    void save(OperationalAlert alert);

    /**
     * Load all OPEN alerts for a tenant, ordered by severity (CRITICAL first)
     * then by {@code raisedAt} descending. Used by the dashboard (DA-OPS-01).
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @return OPEN alerts; empty list if none
     */
    List<OperationalAlert> findOpen(String tenantId);

    /**
     * Load alerts for a tenant filtered by category and status.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param category the alert category to filter on
     * @param status   the alert status to filter on
     * @return matching alerts ordered by {@code raisedAt} descending; empty list if none
     */
    List<OperationalAlert> findByCategory(String tenantId, AlertCategory category,
                                           AlertStatus status);

    /**
     * Load all alerts associated with a specific delivery day for a tenant.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param deliveryDay the CET-interpreted delivery day to filter on
     * @return matching alerts ordered by {@code raisedAt} descending; empty list if none
     */
    List<OperationalAlert> findByDeliveryDay(String tenantId, LocalDate deliveryDay);

    /**
     * Acknowledge an alert in place — sets status to ACKNOWLEDGED, records the user
     * and timestamp. Does not require reloading the full entity.
     *
     * @param tenantId        tenant identifier (D-14, Pattern #32)
     * @param alertId         UUID business key of the alert
     * @param acknowledgedBy  user identifier who acknowledged the alert
     * @param acknowledgedAt  instant of acknowledgement (UTC)
     */
    void acknowledge(String tenantId, UUID alertId, String acknowledgedBy,
                     Instant acknowledgedAt);

    /**
     * Resolve an alert in place — sets status to RESOLVED and records the timestamp.
     * Does not require reloading the full entity.
     *
     * @param tenantId   tenant identifier (D-14, Pattern #32)
     * @param alertId    UUID business key of the alert
     * @param resolvedAt instant of resolution (UTC)
     */
    void resolve(String tenantId, UUID alertId, Instant resolvedAt);

    /**
     * Count alerts per category for a given status.
     * Used by the dashboard alert badge counts (DA-OPS-01).
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param status   the alert status to count
     * @return map of category to count; categories with zero alerts may be omitted
     */
    Map<AlertCategory, Long> countByStatus(String tenantId, AlertStatus status);
}
