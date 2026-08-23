package com.power.posval.persistence.adapter;

import com.power.posval.domain.port.repository.TARGET2CalendarRepository;
import com.power.posval.persistence.entity.TARGET2CalendarEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

import java.time.LocalDate;

/**
 * JPA adapter for {@link TARGET2CalendarRepository}.
 *
 * <p>System-level (tenant-independent) — no {@code tenantId} in any query.
 * The TARGET2 calendar is a shared fact used by the ECC D+2 settlement date
 * computation (DA-SET-02).
 *
 * <p>{@link #nextBusinessDay} and {@link #nthBusinessDayAfter} iterate forward one
 * day at a time, issuing a point-lookup per day until a business day is found. This
 * is correct and safe because:
 * <ul>
 *   <li>The TARGET2 calendar is fully populated for the relevant year range.</li>
 *   <li>There are never more than ~8 consecutive non-business days (Easter + surrounding
 *       weekends), so at most ~8 iterations per call.</li>
 *   <li>The query hits a primary-key lookup on {@code calendar_date DATE PK}.</li>
 * </ul>
 *
 * <p>Pattern #18 (Port + Adapter), S6.1.
 */
public class JpaTargetCalendarRepository implements TARGET2CalendarRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaTargetCalendarRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Single-row primary-key lookup. Returns {@code false} if the date is not in the
     * calendar (treats unknown dates as non-business days — conservative safe default).
     * DA-SET-02, S5.1.
     */
    @Override
    public boolean isBusinessDay(LocalDate date) {
        try {
            TARGET2CalendarEntity entity = emProvider.get()
                .find(TARGET2CalendarEntity.class, date);
            return entity != null && entity.isBusinessDay();
        } catch (Exception e) {
            // Treat lookup failures as non-business day (defensive)
            return false;
        }
    }

    /**
     * Iterate forward from the given date until a TARGET2 business day is found.
     * Maximum iteration depth is bounded by the longest known non-business day run
     * in the TARGET2 calendar (~8 days around Easter). DA-SET-02, S5.1.
     */
    @Override
    public LocalDate nextBusinessDay(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        // Safety guard: limit to 30 iterations to fail fast if calendar is incomplete
        for (int i = 0; i < 30; i++) {
            if (isBusinessDay(candidate)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        throw new IllegalStateException(
            "Could not find next TARGET2 business day within 30 days of: " + date
            + ". Calendar may be incomplete.");
    }

    /**
     * Advance {@code n} TARGET2 business days from {@code date}.
     * {@code nthBusinessDayAfter(date, 1)} == {@link #nextBusinessDay(LocalDate)}.
     * Used for ECC D+2 convention: {@code computePaymentDate(deliveryDay, 2)}. DA-SET-02.
     *
     * @throws IllegalArgumentException if {@code n < 1}
     */
    @Override
    public LocalDate nthBusinessDayAfter(LocalDate date, int n) {
        if (n < 1) {
            throw new IllegalArgumentException(
                "n must be >= 1, got: " + n);
        }
        LocalDate result = date;
        for (int i = 0; i < n; i++) {
            result = nextBusinessDay(result);
        }
        return result;
    }
}
