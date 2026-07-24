package com.power.posval.domain.model;

import java.time.Duration;

/** V3.0 §3.2.1, S3. */
public enum TimeGranularity {
    MIN_5(Duration.ofMinutes(5)),
    MIN_15(Duration.ofMinutes(15)),
    MIN_30(Duration.ofMinutes(30)),
    HOURLY(Duration.ofHours(1)),
    DAILY(null),
    MONTHLY(null);

    private final Duration fixedDuration;

    TimeGranularity(Duration fixedDuration) {
        this.fixedDuration = fixedDuration;
    }

    public boolean isFixedDuration() { return fixedDuration != null; }

    public Duration getFixedDuration() {
        if (fixedDuration == null) {
            throw new UnsupportedOperationException(
                name() + " has variable duration");
        }
        return fixedDuration;
    }

    public boolean isSubDaily() {
        return this == MIN_5 || this == MIN_15 || this == MIN_30 || this == HOURLY;
    }
}
