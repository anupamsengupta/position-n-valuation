package com.power.posval.domain.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Command to record a set of per-interval nominations for a balancing group
 * on a given delivery day. Each interval in {@code intervals} will produce
 * one NominationRecord with an auto-incremented nominationVersion relative to
 * the existing maximum version for that (balancingGroupId, intervalStart).
 * Pattern #17, S4.6, DA-VOL-03.
 */
public record RecordNomination(
    String tenantId,
    String balancingGroupId,
    LocalDate deliveryDay,
    List<NominationInterval> intervals,
    Instant nominationTimestamp,
    String submittedBy
) {
    public RecordNomination {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        Objects.requireNonNull(intervals, "intervals");
        Objects.requireNonNull(nominationTimestamp, "nominationTimestamp");
        Objects.requireNonNull(submittedBy, "submittedBy");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (balancingGroupId.isBlank()) {
            throw new IllegalArgumentException("balancingGroupId must not be blank");
        }
        if (intervals.isEmpty()) {
            throw new IllegalArgumentException("intervals must not be empty");
        }
        intervals = List.copyOf(intervals);
    }
}
