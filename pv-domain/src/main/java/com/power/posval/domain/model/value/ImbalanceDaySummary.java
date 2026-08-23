package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Daily imbalance summary component within an ImbalanceMonthSummary.
 * {@code imbalanceMwh} is the net energy imbalance for the day;
 * {@code imbalanceAmount} is the monetary settlement amount in the parent record's currency.
 * Pattern #3, S5.2, DA-SET-04.
 */
public record ImbalanceDaySummary(
    LocalDate day,
    BigDecimal imbalanceMwh,
    BigDecimal imbalanceAmount
) {
    public ImbalanceDaySummary {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(imbalanceMwh, "imbalanceMwh");
        Objects.requireNonNull(imbalanceAmount, "imbalanceAmount");
    }
}
