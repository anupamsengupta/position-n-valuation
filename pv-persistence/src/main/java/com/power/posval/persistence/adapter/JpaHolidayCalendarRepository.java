package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.HolidayCalendar;
import com.power.posval.domain.port.repository.HolidayCalendarRepository;
import com.power.posval.persistence.entity.HolidayCalendarEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.List;

/**
 * JPA adapter for {@link HolidayCalendarRepository}.
 *
 * <p>System-level (tenant-independent) — no {@code tenantId} in any query.
 * Public holidays are shared facts, not per-tenant configuration (S5.1 note).
 * Used by the block order decomposition engine to exclude holidays from
 * PEAK block intervals (DA-VOL-02).
 *
 * <p>Pattern #18 (Port + Adapter), S6.1.
 */
public class JpaHolidayCalendarRepository implements HolidayCalendarRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaHolidayCalendarRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Point lookup: returns true if a holiday entry exists for (zone, date). DA-VOL-02.
     * Uses COUNT to avoid loading the entity when only a boolean is needed.
     */
    @Override
    public boolean isHoliday(String zone, LocalDate date) {
        Long count = emProvider.get()
            .createQuery("""
                SELECT COUNT(e) FROM HolidayCalendarEntity e
                WHERE e.zone        = :zone
                  AND e.holidayDate = :date
                """, Long.class)
            .setParameter("zone", zone)
            .setParameter("date", date)
            .getSingleResult();
        return count > 0;
    }

    /**
     * Load all holiday entries for a zone within a calendar year, ordered by date.
     * Used by the decomposition engine to bulk-load the holiday set once per import.
     * DA-VOL-02, S5.1.
     */
    @Override
    public List<HolidayCalendar> findByZoneAndYear(String zone, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd   = LocalDate.of(year + 1, 1, 1); // exclusive

        return emProvider.get()
            .createQuery("""
                SELECT e FROM HolidayCalendarEntity e
                WHERE e.zone        = :zone
                  AND e.holidayDate >= :yearStart
                  AND e.holidayDate <  :yearEnd
                ORDER BY e.holidayDate ASC
                """, HolidayCalendarEntity.class)
            .setParameter("zone", zone)
            .setParameter("yearStart", yearStart)
            .setParameter("yearEnd", yearEnd)
            .getResultStream()
            .map(HolidayCalendarEntity::toDomain)
            .toList();
    }
}
