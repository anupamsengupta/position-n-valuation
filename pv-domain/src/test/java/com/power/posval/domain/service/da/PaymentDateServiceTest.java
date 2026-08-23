package com.power.posval.domain.service.da;

import com.power.posval.domain.port.repository.TARGET2CalendarRepository;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultPaymentDateService}.
 *
 * <p>Uses an in-memory stub TARGET2 calendar. All calendar entries are computed from
 * a minimal set: Monday–Friday are business days; Saturday–Sunday and any dates in
 * the stubHolidays map are non-business days. DA-SET-02, Pattern #18.
 */
class PaymentDateServiceTest {

    // ---------------------------------------------------------------------------
    // Stub calendar: Mon-Fri are business days, Sat-Sun are not, plus explicit holidays
    // ---------------------------------------------------------------------------

    private static final class StubTarget2Calendar implements TARGET2CalendarRepository {

        private final java.util.Set<LocalDate> holidays;

        StubTarget2Calendar(LocalDate... holidays) {
            this.holidays = new java.util.HashSet<>();
            for (LocalDate h : holidays) {
                this.holidays.add(h);
            }
        }

        @Override
        public boolean isBusinessDay(LocalDate date) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
            return !holidays.contains(date);
        }

        @Override
        public LocalDate nextBusinessDay(LocalDate date) {
            LocalDate d = date.plusDays(1);
            while (!isBusinessDay(d)) d = d.plusDays(1);
            return d;
        }

        @Override
        public LocalDate nthBusinessDayAfter(LocalDate date, int n) {
            LocalDate d = date;
            for (int i = 0; i < n; i++) {
                d = nextBusinessDay(d);
            }
            return d;
        }
    }

    // ---------------------------------------------------------------------------
    // D+2 normal week: Wednesday 2026-09-16 -> Friday 2026-09-18
    // ---------------------------------------------------------------------------

    @Test
    void d2_onWednesday_landsFriday() {
        DefaultPaymentDateService svc = new DefaultPaymentDateService(new StubTarget2Calendar());
        LocalDate deliveryDay  = LocalDate.of(2026, 9, 16); // Wednesday
        LocalDate paymentDate  = svc.computePaymentDate(deliveryDay, 2);
        assertEquals(LocalDate.of(2026, 9, 18), paymentDate); // Friday
    }

    // ---------------------------------------------------------------------------
    // D+2 crossing weekend: Thursday 2026-09-17 -> Monday 2026-09-21
    // (Fri is +1, Mon is +2 — skip Sat and Sun)
    // ---------------------------------------------------------------------------

    @Test
    void d2_onThursday_skipsWeekend_landsMonday() {
        DefaultPaymentDateService svc = new DefaultPaymentDateService(new StubTarget2Calendar());
        LocalDate deliveryDay = LocalDate.of(2026, 9, 17); // Thursday
        LocalDate paymentDate = svc.computePaymentDate(deliveryDay, 2);
        assertEquals(LocalDate.of(2026, 9, 21), paymentDate); // Monday
    }

    // ---------------------------------------------------------------------------
    // D+2 where +1 business day lands on a TARGET2 holiday:
    // Tuesday 2026-12-22 + D2 → Wed 2026-12-23 is holiday → Thu 2026-12-24 is holiday
    //                          → 2026-12-23 holiday, 2026-12-24 holiday, land on 2026-12-28 Mon
    //
    // Simpler: Thursday 2026-12-24 delivery (Xmas Eve, holiday), +1 => Mon 28, +2 => Tue 29
    // Let's use: delivery = Monday 2026-12-21
    // +1 business day: skip Tue 22, it's a business day => Tue 22
    // +2 business day: skip Wed 23 (holiday) => Thu 24 (holiday) => Fri 25 (holiday/weekend) =>
    //                  Mon 28
    //
    // holidays: Wed Dec 23, Thu Dec 24, Fri Dec 25
    // delivery Mon Dec 21 -> +1 = Tue Dec 22, +2 skip Wed 23 (hol), Thu 24 (hol), Fri 25 (hol+sat),
    //   land Mon Dec 28
    // ---------------------------------------------------------------------------

    @Test
    void d2_crossingTarget2Holiday_skipsHoliday() {
        // Holidays: 2026-12-23, 2026-12-24, 2026-12-25
        StubTarget2Calendar cal = new StubTarget2Calendar(
            LocalDate.of(2026, 12, 23),
            LocalDate.of(2026, 12, 24),
            LocalDate.of(2026, 12, 25)
        );
        DefaultPaymentDateService svc = new DefaultPaymentDateService(cal);

        LocalDate deliveryDay = LocalDate.of(2026, 12, 21); // Monday
        LocalDate paymentDate = svc.computePaymentDate(deliveryDay, 2);

        // +1 business day from Mon Dec 21 = Tue Dec 22 (normal)
        // +2 business day from Mon Dec 21 = next after Tue Dec 22:
        //   Wed Dec 23 = holiday, skip; Thu Dec 24 = holiday, skip; Fri Dec 25 = holiday, skip;
        //   Sat Dec 26 = weekend, skip; Sun Dec 27 = weekend, skip; Mon Dec 28 = business day
        assertEquals(LocalDate.of(2026, 12, 28), paymentDate);
    }

    // ---------------------------------------------------------------------------
    // D+1 on a normal weekday
    // ---------------------------------------------------------------------------

    @Test
    void d1_onMonday_landsTuesday() {
        DefaultPaymentDateService svc = new DefaultPaymentDateService(new StubTarget2Calendar());
        LocalDate deliveryDay = LocalDate.of(2026, 9, 14); // Monday
        LocalDate paymentDate = svc.computePaymentDate(deliveryDay, 1);
        assertEquals(LocalDate.of(2026, 9, 15), paymentDate);
    }

    // ---------------------------------------------------------------------------
    // Validation: businessDaysAfter < 1 throws
    // ---------------------------------------------------------------------------

    @Test
    void businessDaysAfterZero_throws() {
        DefaultPaymentDateService svc = new DefaultPaymentDateService(new StubTarget2Calendar());
        assertThrows(IllegalArgumentException.class,
            () -> svc.computePaymentDate(LocalDate.of(2026, 9, 16), 0));
    }

    @Test
    void businessDaysAfterNegative_throws() {
        DefaultPaymentDateService svc = new DefaultPaymentDateService(new StubTarget2Calendar());
        assertThrows(IllegalArgumentException.class,
            () -> svc.computePaymentDate(LocalDate.of(2026, 9, 16), -1));
    }

    @Test
    void nullDeliveryDay_throwsNpe() {
        DefaultPaymentDateService svc = new DefaultPaymentDateService(new StubTarget2Calendar());
        assertThrows(NullPointerException.class, () -> svc.computePaymentDate(null, 2));
    }
}
