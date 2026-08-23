package com.power.posval.app.calendar;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.MarketCalendarPort;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Simulator-scope stub adapter for {@link MarketCalendarPort} (D-14).
 *
 * <p>Provides DST-correct interval generation for EU physical power delivery days
 * using {@code ZoneId("Europe/Berlin")} and {@code java.time}. This is a
 * simulator/testing adapter — the production host must bind a real implementation
 * that consults {@code HolidayCalendarRepository} for correct PEAK classification
 * and supports multiple bidding zones (A-1, S9.3).
 *
 * <p>A delivery day in Central European Time (CET/CEST) runs from midnight CET on
 * the delivery date to midnight CET on the following date. Because of DST transitions:
 * <ul>
 *   <li>Standard day: 96 quarter-hourly (QH) intervals (24h x 4)</li>
 *   <li>Spring-forward (clock moves CET to CEST): 92 QH intervals (23h x 4)</li>
 *   <li>Fall-back (clock moves CEST to CET): 100 QH intervals (25h x 4)</li>
 * </ul>
 *
 * <p>EPEX PEAK definition used here: 08:00-20:00 CET on weekdays (Monday-Friday).
 * Public holidays are NOT checked (stub limitation per A-1). FR-025.
 *
 * <p>Pattern #18 (Port + Adapter), S6.1, A-1, D-14.
 */
@Singleton
public class StubMarketCalendarAdapter implements MarketCalendarPort {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final LocalTime PEAK_START = LocalTime.of(8, 0);
    private static final LocalTime PEAK_END = LocalTime.of(20, 0);
    private static final Set<DayOfWeek> PEAK_WEEKDAYS = Set.of(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    );

    @Inject
    public StubMarketCalendarAdapter() {}

    @Override
    public List<DeliveryPeriod> intervalsForDay(String biddingZone, LocalDate deliveryDay,
                                                 TimeGranularity granularity) {
        if (!granularity.isFixedDuration()) {
            throw new UnsupportedOperationException(
                "Granularity " + granularity + " has no fixed duration; cannot slice into intervals");
        }

        ZonedDateTime dayStart = deliveryDay.atStartOfDay(CET);
        ZonedDateTime dayEnd   = deliveryDay.plusDays(1).atStartOfDay(CET);
        Duration sliceWidth    = granularity.getFixedDuration();

        List<DeliveryPeriod> intervals = new ArrayList<>();
        ZonedDateTime sliceStart = dayStart;
        while (sliceStart.isBefore(dayEnd)) {
            ZonedDateTime sliceEnd = sliceStart.plus(sliceWidth);
            if (sliceEnd.isAfter(dayEnd)) {
                sliceEnd = dayEnd;
            }
            intervals.add(new DeliveryPeriod(sliceStart, sliceEnd, CET));
            sliceStart = sliceEnd;
        }
        return List.copyOf(intervals);
    }

    @Override
    public int intervalCount(String biddingZone, LocalDate deliveryDay,
                              TimeGranularity granularity) {
        return intervalsForDay(biddingZone, deliveryDay, granularity).size();
    }

    @Override
    public boolean isPeakInterval(String biddingZone, DeliveryPeriod interval) {
        ZonedDateTime startCet = interval.start().withZoneSameInstant(CET);
        LocalTime timeCet     = startCet.toLocalTime();
        DayOfWeek dayOfWeek   = startCet.getDayOfWeek();

        boolean isWeekday = PEAK_WEEKDAYS.contains(dayOfWeek);
        boolean isPeakHour = !timeCet.isBefore(PEAK_START) && timeCet.isBefore(PEAK_END);

        return isWeekday && isPeakHour;
    }

    @Override
    public DeliveryPeriod deliveryDayBoundaries(String biddingZone, LocalDate deliveryDay) {
        ZonedDateTime dayStart = deliveryDay.atStartOfDay(CET);
        ZonedDateTime dayEnd   = deliveryDay.plusDays(1).atStartOfDay(CET);
        return new DeliveryPeriod(dayStart, dayEnd, CET);
    }
}
