package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Value object returned by {@code InstrumentCalculationStrategy#computeFees}.
 * Carries the fee line items and the total amount computed for a delivery day.
 *
 * <p>Distinct from {@link ExchangeFeeResult} in that {@code FeeResult} is the generic
 * instrument-strategy return type; {@code ExchangeFeeResult} is the richer DTO returned
 * by the standalone {@code ExchangeFeeService} (which also carries delivery-day metadata
 * and gross-volume context).
 *
 * <p>Pattern #3, S9b.2.
 */
public record FeeResult(
    List<FeeLineItem> items,
    BigDecimal totalFeeAmount,
    String currency
) {
    public FeeResult {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(totalFeeAmount, "totalFeeAmount");
        Objects.requireNonNull(currency,       "currency");
    }
}
