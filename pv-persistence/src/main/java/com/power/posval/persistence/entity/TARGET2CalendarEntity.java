package com.power.posval.persistence.entity;

import com.power.posval.domain.model.TARGET2Calendar;
import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * JPA entity for the {@code target2_calendar} table.
 * System-level (tenant-independent) TARGET2 banking day calendar.
 * Primary key is {@code calendar_date} (a DATE column) — no surrogate BIGINT needed
 * because dates are naturally unique and the table is reference data loaded once.
 * Pattern #23 note: system-level reference tables with natural PKs are exempt from
 * the surrogate-sequence rule (S7.1 — "system-level" tables noted in spec).
 * DA-SET-02, S4.4, S7.1.
 */
@Entity
@Table(name = "target2_calendar", schema = "da")
public class TARGET2CalendarEntity {

    /**
     * Natural primary key: the calendar date.
     * This is a system-level reference table with at most ~365 rows/year;
     * a surrogate BIGINT sequence would add no value here (spec S7.1 allows this
     * for the TARGET2 table which has {@code calendar_date DATE PK}).
     */
    @Id
    @Column(name = "calendar_date", nullable = false)
    private LocalDate calendarDate;

    @Column(name = "is_business_day", nullable = false)
    private boolean isBusinessDay;

    // --- JPA ---

    protected TARGET2CalendarEntity() {}

    // --- Accessors ---

    public LocalDate getCalendarDate() { return calendarDate; }
    public void setCalendarDate(LocalDate calendarDate) { this.calendarDate = calendarDate; }

    public boolean isBusinessDay() { return isBusinessDay; }
    public void setBusinessDay(boolean businessDay) { isBusinessDay = businessDay; }

    // --- Domain conversion ---

    /**
     * Convert this entity to the domain record.
     * FR-nnn: DA-SET-02 payment date computation relies on this calendar.
     */
    public TARGET2Calendar toDomain() {
        return new TARGET2Calendar(this.calendarDate, this.isBusinessDay);
    }

    /** Factory: create entity from domain record. */
    public static TARGET2CalendarEntity fromDomain(TARGET2Calendar d) {
        TARGET2CalendarEntity e = new TARGET2CalendarEntity();
        e.setCalendarDate(d.calendarDate());
        e.setBusinessDay(d.isBusinessDay());
        return e;
    }
}
