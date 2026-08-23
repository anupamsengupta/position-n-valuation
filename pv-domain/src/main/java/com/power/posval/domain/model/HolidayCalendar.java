package com.power.posval.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * System-level (tenant-independent) public holiday reference entry.
 * One row per (zone, holidayDate) pair.
 * Used by the block order decomposition engine to exclude holidays from
 * PEAK block intervals (DA-VOL-02).
 * Pattern #3, S4.4.
 */
public record HolidayCalendar(
    UUID calendarId,
    String zone,
    LocalDate holidayDate,
    String holidayName
) {
    public HolidayCalendar {
        Objects.requireNonNull(calendarId, "calendarId");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(holidayDate, "holidayDate");
        Objects.requireNonNull(holidayName, "holidayName");
        if (zone.isBlank()) {
            throw new IllegalArgumentException("zone must not be blank");
        }
        if (holidayName.isBlank()) {
            throw new IllegalArgumentException("holidayName must not be blank");
        }
    }
}
