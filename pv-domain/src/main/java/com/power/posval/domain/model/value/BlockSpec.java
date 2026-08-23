package com.power.posval.domain.model.value;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Set;

/**
 * Specification of a block order type for DA auction decomposition.
 * Defines the CET time window, applicable weekdays, and holiday calendar reference
 * used by the block order decomposition engine to expand block orders into
 * constituent 15-min or hourly intervals.
 * Pattern #3, S4.1, DA-VOL-02.
 */
public record BlockSpec(
    String blockType,
    LocalTime startTime,
    LocalTime endTime,
    Set<DayOfWeek> applicableDays,
    String holidayCalendarRef
) {
    public BlockSpec {
        Objects.requireNonNull(blockType, "blockType");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        Objects.requireNonNull(applicableDays, "applicableDays");
        if (blockType.isBlank()) {
            throw new IllegalArgumentException("blockType must not be blank");
        }
        if (applicableDays.isEmpty()) {
            throw new IllegalArgumentException("applicableDays must not be empty");
        }
        // holidayCalendarRef is nullable (null = no holiday exclusion)
        // applicableDays is defensively copied to preserve immutability
        applicableDays = Set.copyOf(applicableDays);
    }
}
