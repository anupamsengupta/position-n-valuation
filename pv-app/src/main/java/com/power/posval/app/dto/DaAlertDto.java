package com.power.posval.app.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JSON-serializable DTO for {@code OperationalAlert} domain model (DA-OPS-01).
 */
public record DaAlertDto(
    UUID alertId,
    String tenantId,
    String category,
    String severity,
    String alertType,
    String message,
    LocalDate deliveryDay,
    String biddingZone,
    String sourceEventId,
    Instant raisedAt,
    String status,
    String acknowledgedBy,
    Instant acknowledgedAt,
    Instant resolvedAt
) {}
