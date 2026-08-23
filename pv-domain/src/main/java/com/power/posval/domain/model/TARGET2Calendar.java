package com.power.posval.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * System-level (tenant-independent) TARGET2 banking calendar entry.
 * Used by PaymentDateService to compute DA ECC settlement payment dates
 * on a D+2 business day convention (DA-SET-02).
 * Pattern #3, S4.4.
 */
public record TARGET2Calendar(
    LocalDate calendarDate,
    boolean isBusinessDay
) {
    public TARGET2Calendar {
        Objects.requireNonNull(calendarDate, "calendarDate");
    }
}
