package com.power.posval.domain.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A single 15-min interval nomination volume, used as a component of RecordNomination.
 * Half-open: [intervalStart, intervalEnd).
 * Pattern #17, S4.6, DA-VOL-03.
 */
public record NominationInterval(
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal volumeMw
) {
    public NominationInterval {
        Objects.requireNonNull(intervalStart, "intervalStart");
        Objects.requireNonNull(intervalEnd, "intervalEnd");
        Objects.requireNonNull(volumeMw, "volumeMw");
        if (!intervalEnd.isAfter(intervalStart)) {
            throw new IllegalArgumentException(
                "intervalEnd must be after intervalStart");
        }
    }
}
