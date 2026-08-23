package com.power.posval.domain.port;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.value.DeliveryPeriod;

import java.time.LocalDate;
import java.util.List;

/**
 * Port interface for DST-correct market calendar operations.
 *
 * <p>Encapsulates all knowledge about how delivery days are divided into intervals for a
 * given bidding zone. For Central European zones, the delivery day may contain 92, 96, or
 * 100 quarter-hourly slots depending on DST transitions (FR-024, FR-025, A-1).
 *
 * <p>If a {@code MarketCalendar} implementation already exists in the host environment
 * this port is bound to it. Where no implementation exists (e.g. early simulator), a
 * {@code StubMarketCalendarAdapter} in {@code pv-app} provides a DST-correct fallback
 * using {@code ZoneId("Europe/Berlin")} and {@code java.time} (S6.1, A-1, D-13).
 *
 * <p>Pattern #18 (Port Interface), S5.1.
 */
public interface MarketCalendarPort {

    /**
     * Generate the ordered list of delivery intervals that make up a delivery day
     * for a given bidding zone at the requested granularity.
     *
     * <p>On a standard day, {@link TimeGranularity#QUARTER_HOURLY} yields 96 intervals.
     * On a DST spring-forward day it yields 92; on a DST fall-back day it yields 100.
     *
     * @param biddingZone the bidding zone code (e.g. {@code "DE_LU"}, {@code "FR"})
     * @param deliveryDay the CET-interpreted delivery date
     * @param granularity the desired interval granularity
     * @return ordered, non-overlapping, gap-free intervals covering the full delivery day;
     *         never null, never empty
     */
    List<DeliveryPeriod> intervalsForDay(String biddingZone, LocalDate deliveryDay,
                                          TimeGranularity granularity);

    /**
     * Return the count of intervals in a delivery day at the requested granularity.
     *
     * <p>This is a convenience method equivalent to {@code intervalsForDay(...).size()}
     * but may be implemented more efficiently (e.g. without constructing all objects).
     *
     * @param biddingZone the bidding zone code
     * @param deliveryDay the CET-interpreted delivery date
     * @param granularity the desired interval granularity
     * @return the number of intervals; 92, 96, or 100 for {@code QUARTER_HOURLY}
     */
    int intervalCount(String biddingZone, LocalDate deliveryDay, TimeGranularity granularity);

    /**
     * Return {@code true} if the given interval falls within the PEAK hours for the
     * bidding zone. EPEX DE_LU peak = hours 8–20 CET on business days (FR-025).
     *
     * @param biddingZone the bidding zone code
     * @param interval    the interval to classify
     * @return {@code true} if this interval is a peak-hour interval for the zone
     */
    boolean isPeakInterval(String biddingZone, DeliveryPeriod interval);

    /**
     * Return the full delivery day as a single {@link DeliveryPeriod} from midnight CET
     * to midnight CET (24 h wall-clock, DST-aware).
     *
     * @param biddingZone the bidding zone code
     * @param deliveryDay the CET-interpreted delivery date
     * @return the delivery day boundaries as a single half-open period
     */
    DeliveryPeriod deliveryDayBoundaries(String biddingZone, LocalDate deliveryDay);
}
