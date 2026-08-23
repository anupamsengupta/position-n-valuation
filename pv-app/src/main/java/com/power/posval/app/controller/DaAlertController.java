package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.DaAlertDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.port.service.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulator REST controller for DA operational alert queries and lifecycle management
 * (S9.4, DA-OPS-01, S16.2.2).
 *
 * <p>Alert delivery in v1 is pull-based (UI polling). Push notification via
 * email/webhook is deferred to OQ-DA-OPS-2. S2.2.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/da/alerts}          — all open alerts for a tenant</li>
 *   <li>{@code GET  /api/da/alerts/counts}   — alert counts by category</li>
 *   <li>{@code PUT  /api/da/alerts/{id}/ack} — acknowledge an open alert</li>
 *   <li>{@code PUT  /api/da/alerts/{id}/resolve} — resolve an acknowledged alert</li>
 * </ul>
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/alerts")
public class DaAlertController {

    private static final Logger log = LoggerFactory.getLogger(DaAlertController.class);

    private final OperationalAlertService operationalAlertService;
    private final TransactionalExecutor txExecutor;

    public DaAlertController(OperationalAlertService operationalAlertService,
                              TransactionalExecutor txExecutor) {
        this.operationalAlertService = operationalAlertService;
        this.txExecutor = txExecutor;
    }

    /**
     * Retrieve all open operational alerts for a tenant (S9.4, DA-OPS-01).
     *
     * <p>{@code GET /api/da/alerts?tenantId=...}
     *
     * <p>Results are ordered by severity (CRITICAL first) then by {@code raisedAt}
     * descending (most recent first).
     */
    @GetMapping
    public ApiResponse<List<DaAlertDto>> getOpenAlerts(
            @RequestParam(defaultValue = "default") String tenantId) {
        log.info("GET /api/da/alerts tenantId={}", tenantId);
        List<OperationalAlert> alerts = txExecutor.execute(
            () -> operationalAlertService.getOpenAlerts(tenantId));
        List<DaAlertDto> dtos = alerts.stream().map(this::toDto).toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * Retrieve alert counts by category for a tenant (S9.4, DA-OPS-01).
     *
     * <p>{@code GET /api/da/alerts/counts?tenantId=...}
     *
     * <p>Suitable for dashboard badge counts.
     */
    @GetMapping("/counts")
    public ApiResponse<Map<AlertCategory, Long>> getAlertCounts(
            @RequestParam(defaultValue = "default") String tenantId) {
        log.info("GET /api/da/alerts/counts tenantId={}", tenantId);
        Map<AlertCategory, Long> counts = txExecutor.execute(
            () -> operationalAlertService.getAlertCounts(tenantId));
        return ApiResponse.ok(counts);
    }

    /**
     * Acknowledge an open alert (S9.4, DA-OPS-01).
     *
     * <p>{@code PUT /api/da/alerts/{id}/ack?user=...&tenantId=...}
     *
     * <p>Transitions the alert from {@code OPEN} to {@code ACKNOWLEDGED}.
     */
    @PutMapping("/{id}/ack")
    public ApiResponse<Void> acknowledgeAlert(
            @PathVariable String id,
            @RequestParam String user,
            @RequestParam(defaultValue = "default") String tenantId) {
        log.info("PUT /api/da/alerts/{}/ack tenantId={} user={}", id, tenantId, user);
        txExecutor.execute(() -> {
            operationalAlertService.acknowledgeAlert(tenantId, UUID.fromString(id), user);
            return null;
        });
        return ApiResponse.ok(null, "Alert acknowledged");
    }

    /**
     * Resolve an alert (S16.2.2, DA-OPS-01).
     *
     * <p>{@code PUT /api/da/alerts/{id}/resolve?tenantId=...}
     *
     * <p>Transitions the alert from {@code OPEN} or {@code ACKNOWLEDGED} to {@code RESOLVED}.
     * If the alert is already {@code RESOLVED}, the service raises an
     * {@link com.power.posval.domain.exception.IllegalStateTransitionException}.
     */
    @PutMapping("/{id}/resolve")
    public ApiResponse<Void> resolveAlert(
            @PathVariable String id,
            @RequestParam(defaultValue = "default") String tenantId) {
        log.info("PUT /api/da/alerts/{}/resolve tenantId={}", id, tenantId);
        txExecutor.execute(() -> {
            operationalAlertService.resolveAlert(tenantId, UUID.fromString(id));
            return null;
        });
        return ApiResponse.ok(null, "Alert resolved");
    }

    private DaAlertDto toDto(OperationalAlert a) {
        return new DaAlertDto(
            a.alertId(),
            a.tenantId(),
            a.category().name(),
            a.severity().name(),
            a.alertType(),
            a.message(),
            a.deliveryDay(),
            a.biddingZone(),
            a.sourceEventId(),
            a.raisedAt(),
            a.status().name(),
            a.acknowledgedBy(),
            a.acknowledgedAt(),
            a.resolvedAt()
        );
    }
}
