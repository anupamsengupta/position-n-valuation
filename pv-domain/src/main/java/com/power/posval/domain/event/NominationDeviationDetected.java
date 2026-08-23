package com.power.posval.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Emitted by NominationService.compareWithTraded() when the nominated volume
 * for a single 15-min interval deviates from the traded volume.
 * One event per deviating interval.
 * Pattern #14, S4.5, DA-VOL-03.
 */
public record NominationDeviationDetected(
    String tenantId,
    String balancingGroupId,
    LocalDate deliveryDay,
    Instant intervalStart,
    BigDecimal tradedMw,
    BigDecimal nominatedMw,
    Instant eventTime
) {}
