package com.power.posval.persistence.entity;

import com.power.posval.domain.model.HolidayCalendar;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code holiday_calendar} table.
 * System-level (tenant-independent) public holiday reference entry.
 * UNIQUE constraint on (zone, holiday_date) prevents duplicate entries.
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-VOL-02.
 */
@Entity
@Table(name = "holiday_calendar", schema = "da",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_hc_zone_date", columnNames = {"zone", "holiday_date"})
    })
public class HolidayCalendarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hc_seq")
    @SequenceGenerator(name = "hc_seq",
                       sequenceName = "da.holiday_calendar_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "calendar_id", nullable = false, unique = true)
    private UUID calendarId;

    @Column(name = "zone", nullable = false, length = 8)
    private String zone;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "holiday_name", nullable = false, length = 128)
    private String holidayName;

    // --- JPA ---

    protected HolidayCalendarEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getCalendarId() { return calendarId; }
    public void setCalendarId(UUID calendarId) { this.calendarId = calendarId; }

    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }

    public LocalDate getHolidayDate() { return holidayDate; }
    public void setHolidayDate(LocalDate holidayDate) { this.holidayDate = holidayDate; }

    public String getHolidayName() { return holidayName; }
    public void setHolidayName(String holidayName) { this.holidayName = holidayName; }

    // --- Domain conversion ---

    public HolidayCalendar toDomain() {
        return new HolidayCalendar(this.calendarId, this.zone, this.holidayDate, this.holidayName);
    }

    public static HolidayCalendarEntity fromDomain(HolidayCalendar d) {
        HolidayCalendarEntity e = new HolidayCalendarEntity();
        e.setCalendarId(d.calendarId());
        e.setZone(d.zone());
        e.setHolidayDate(d.holidayDate());
        e.setHolidayName(d.holidayName());
        return e;
    }
}
