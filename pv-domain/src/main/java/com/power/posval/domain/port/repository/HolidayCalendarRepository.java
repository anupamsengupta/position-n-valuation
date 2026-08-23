package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.HolidayCalendar;

import java.time.LocalDate;
import java.util.List;

/**
 * Port interface for {@link HolidayCalendar} read access.
 *
 * <p>Holiday calendars are system-level reference data — they are intentionally
 * tenant-independent because public holidays are shared facts, not tenant configuration
 * (S5.1 note, S10.1). No {@code tenantId} parameter appears on any method.
 *
 * <p>Used by the block order decomposition engine to exclude holidays from PEAK block
 * intervals (DA-VOL-02).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface HolidayCalendarRepository {

    /**
     * Return {@code true} if the given date is a public holiday in the specified zone.
     *
     * @param zone the zone code, e.g. {@code "DE"}, {@code "FR"}, {@code "AT"}
     * @param date the date to check
     * @return {@code true} if there is a holiday calendar entry for this zone and date
     */
    boolean isHoliday(String zone, LocalDate date);

    /**
     * Load all holiday calendar entries for a zone within a given year.
     *
     * @param zone the zone code, e.g. {@code "DE"}, {@code "FR"}
     * @param year the calendar year (e.g. {@code 2026})
     * @return all holiday entries for the zone in the year, ordered by date; empty list if none
     */
    List<HolidayCalendar> findByZoneAndYear(String zone, int year);
}
