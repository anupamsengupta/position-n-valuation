package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JSON-serializable DTO for {@code NominationRecord} domain model (DA-VOL-03).
 */
public record DaNominationRecordDto(
    UUID nominationId,
    String tenantId,
    String balancingGroupId,
    LocalDate deliveryDay,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal nominatedVolumeMw,
    Instant nominationTimestamp,
    int nominationVersion,
    String submittedBy
) {}
