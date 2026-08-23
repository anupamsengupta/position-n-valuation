package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An applicable exchange fee rate for a specific fee type and (optionally) member tier.
 * {@code ratePerMwh} is stored at PRICE scale (8 decimal places, per NumericPrecision).
 * {@code memberTier} is nullable — null means the default tier applies.
 * Pattern #3, S4.1, DA-SET-03.
 */
public record FeeRate(
    BigDecimal ratePerMwh,
    String feeType,
    String memberTier
) {
    public FeeRate {
        Objects.requireNonNull(ratePerMwh, "ratePerMwh");
        Objects.requireNonNull(feeType, "feeType");
        if (feeType.isBlank()) {
            throw new IllegalArgumentException("feeType must not be blank");
        }
        if (ratePerMwh.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ratePerMwh must be non-negative");
        }
        // memberTier is nullable
    }
}
