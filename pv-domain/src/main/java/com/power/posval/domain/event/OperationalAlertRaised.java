package com.power.posval.domain.event;

import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by OperationalAlertService when a new OperationalAlert is persisted.
 * Used by notification adapters (v1: pull-based UI only; v2: webhook/email — OQ-DA-OPS-2).
 * Pattern #14, S4.5, DA-OPS-01.
 */
public record OperationalAlertRaised(
    String tenantId,
    UUID alertId,
    AlertCategory category,
    AlertSeverity severity,
    String message,
    Instant eventTime
) {}
