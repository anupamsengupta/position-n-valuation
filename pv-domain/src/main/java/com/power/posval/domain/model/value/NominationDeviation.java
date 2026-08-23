package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Result value from NominationService.compareWithTraded() for a single 15-min interval.
 * Captures the traded MW, nominated MW, and computed deviation for that interval.
 * {@code deviationMw = nominatedMw - tradedMw} (positive = over-nominated).
 * Pattern #3, S5.2, DA-VOL-03.
 */
public record NominationDeviation(
    Instant intervalStart,
    BigDecimal tradedMw,
    BigDecimal nominatedMw,
    BigDecimal deviationMw
) {
    public NominationDeviation {
        Objects.requireNonNull(intervalStart, "intervalStart");
        Objects.requireNonNull(tradedMw, "tradedMw");
        Objects.requireNonNull(nominatedMw, "nominatedMw");
        Objects.requireNonNull(deviationMw, "deviationMw");
    }
}
