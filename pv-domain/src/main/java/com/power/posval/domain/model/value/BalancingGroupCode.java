package com.power.posval.domain.model.value;

import java.util.Objects;

/**
 * TSO-assigned balancing responsible party (BRP) / balancing group code.
 * Composed of the TSO control area identifier and the official BG code.
 * Pattern #3, S4.1, DA-VOL-03.
 */
public record BalancingGroupCode(
    String tsoArea,
    String bgCode
) {
    public BalancingGroupCode {
        Objects.requireNonNull(tsoArea, "tsoArea");
        Objects.requireNonNull(bgCode, "bgCode");
        if (tsoArea.isBlank()) {
            throw new IllegalArgumentException("tsoArea must not be blank");
        }
        if (bgCode.isBlank()) {
            throw new IllegalArgumentException("bgCode must not be blank");
        }
    }

    /** Returns the canonical string form {@code tsoArea/bgCode}. */
    @Override
    public String toString() {
        return tsoArea + "/" + bgCode;
    }
}
