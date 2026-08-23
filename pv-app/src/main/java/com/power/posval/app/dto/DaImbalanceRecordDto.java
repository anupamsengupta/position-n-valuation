package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JSON-serializable DTO for {@code ImbalanceRecord} domain model (DA-SET-04).
 */
public record DaImbalanceRecordDto(
    UUID recordId,
    String tenantId,
    String balancingGroupId,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal nominatedVolumeMw,
    BigDecimal actualDeliveredMw,
    BigDecimal imbalanceVolumeMw,
    BigDecimal imbalanceEnergyMwh,
    BigDecimal imbalancePricePerMwh,
    BigDecimal imbalanceAmount,
    String currency,
    String tsoDataSource,
    Instant tsoPublicationTimestamp,
    int recordVersion,
    Instant computedAt,
    LocalDate deliveryDay
) {}
