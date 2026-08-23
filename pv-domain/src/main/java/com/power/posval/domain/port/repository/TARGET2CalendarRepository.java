package com.power.posval.domain.port.repository;

import java.time.LocalDate;

/**
 * Port interface for the TARGET2 banking day calendar.
 *
 * <p>TARGET2 is the ECB's real-time gross settlement system. TARGET2 business days
 * are the reference for ECC settlement date computation (DA-SET-02, D+2 convention).
 *
 * <p>The calendar is system-level — tenant-independent. No {@code tenantId} parameter
 * appears on any method.
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface TARGET2CalendarRepository {

    /**
     * Return {@code true} if the given date is a TARGET2 business day.
     * Weekends and ECB non-settlement days (e.g. Christmas, New Year) return {@code false}.
     *
     * @param date the date to check
     * @return {@code true} if it is a TARGET2 business day
     */
    boolean isBusinessDay(LocalDate date);

    /**
     * Return the first TARGET2 business day strictly after the given date.
     *
     * @param date the reference date (exclusive)
     * @return the next TARGET2 business day after {@code date}
     */
    LocalDate nextBusinessDay(LocalDate date);

    /**
     * Return the n-th TARGET2 business day after the given date.
     * {@code nthBusinessDayAfter(date, 1)} is equivalent to {@link #nextBusinessDay(LocalDate)}.
     * Used by {@code PaymentDateService} for the ECC D+2 settlement roll convention (DA-SET-02).
     *
     * @param date the start date (the count begins from the day after this date)
     * @param n    the number of business days to advance; must be &gt;= 1
     * @return the n-th TARGET2 business day after {@code date}
     * @throws IllegalArgumentException if {@code n} &lt; 1
     */
    LocalDate nthBusinessDayAfter(LocalDate date, int n);
}
