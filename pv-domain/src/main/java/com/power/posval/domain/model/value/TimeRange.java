package com.power.posval.domain.model.value;

import java.time.Instant;
import java.util.Objects;

/**
 * Half-open time range [from, to) used for bitemporal axes and interval ranges.
 * Pattern #3, FR-036, S1/S3/S5a.
 */
public record TimeRange(Instant from, Instant to) {
    public TimeRange {
        Objects.requireNonNull(from, "from");
        // 'to' may be null for open-ended ranges (e.g., current knowledge)
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
    }

    public static TimeRange open(Instant from) {
        return new TimeRange(from, null);
    }

    public static TimeRange closed(Instant from, Instant to) {
        return new TimeRange(from, to);
    }

    public boolean isOpen() { return to == null; }

    /** True if instant falls within [from, to). */
    public boolean contains(Instant instant) {
        return !instant.isBefore(from) && (to == null || instant.isBefore(to));
    }
}
