package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single line item within an ExchangeFeeResult, representing the contribution
 * of one fee type to the total fee for a delivery day.
 * {@code amount = ratePerMwh * volumeMwh} at MONETARY scale (4 decimal places).
 * Pattern #3, S5.2, DA-SET-03.
 */
public record FeeLineItem(
    String feeType,
    BigDecimal ratePerMwh,
    BigDecimal volumeMwh,
    BigDecimal amount
) {
    public FeeLineItem {
        Objects.requireNonNull(feeType, "feeType");
        Objects.requireNonNull(ratePerMwh, "ratePerMwh");
        Objects.requireNonNull(volumeMwh, "volumeMwh");
        Objects.requireNonNull(amount, "amount");
        if (feeType.isBlank()) {
            throw new IllegalArgumentException("feeType must not be blank");
        }
    }
}
