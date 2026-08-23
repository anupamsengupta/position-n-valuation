package com.power.posval.domain.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Emitted by NominationService after a set of interval nominations for a
 * balancing group and delivery day has been persisted.
 * {@code intervalCount} is the number of NominationRecord rows written.
 * Pattern #14, S4.5, DA-VOL-03.
 */
public record NominationRecorded(
    String tenantId,
    String balancingGroupId,
    LocalDate deliveryDay,
    int intervalCount,
    Instant eventTime
) {}
