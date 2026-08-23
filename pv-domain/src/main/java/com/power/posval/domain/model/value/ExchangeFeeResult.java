package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Exchange fee computation result for a single exchange and delivery day.
 * Returned by ExchangeFeeService.computeForDay().
 * {@code grossVolumeMwh} is the absolute (unsigned) traded volume across all directions.
 * {@code totalFeeAmount} is the sum of all line item amounts at MONETARY scale (4 decimal places).
 * Pattern #3, S5.2, DA-SET-03.
 */
public record ExchangeFeeResult(
    LocalDate deliveryDay,
    BigDecimal grossVolumeMwh,
    List<FeeLineItem> items,
    BigDecimal totalFeeAmount,
    String currency
) {
    public ExchangeFeeResult {
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        Objects.requireNonNull(grossVolumeMwh, "grossVolumeMwh");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(totalFeeAmount, "totalFeeAmount");
        Objects.requireNonNull(currency, "currency");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        items = List.copyOf(items);
    }
}
