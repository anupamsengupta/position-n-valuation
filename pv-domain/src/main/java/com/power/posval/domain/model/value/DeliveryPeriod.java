package com.power.posval.domain.model.value;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Half-open delivery window [start, end) in market-local wall-clock.
 * Interval materialization via MarketCalendar (FR-025).
 * Pattern #3, #8, FR-036, S1.
 */
public record DeliveryPeriod(
    ZonedDateTime start,
    ZonedDateTime end,
    ZoneId deliveryTimezone
) {
    public DeliveryPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(deliveryTimezone, "deliveryTimezone");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                "delivery end must be after start: [%s, %s)".formatted(start, end));
        }
    }

    public static DeliveryPeriod of(ZonedDateTime start, ZonedDateTime end, ZoneId zone) {
        return new DeliveryPeriod(
            start.withZoneSameInstant(zone),
            end.withZoneSameInstant(zone),
            zone
        );
    }

    /** True if this period contains the given instant. */
    public boolean contains(ZonedDateTime instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /**
     * Decompose this delivery period into monthly delivery range blocks.
     * FR-030: one ledger entry per delivery-month block.
     */
    public List<DeliveryRange> toMonthBlocks() {
        YearMonth startYm = YearMonth.from(start);
        YearMonth endYm = YearMonth.from(end.minusNanos(1)); // end is exclusive
        List<DeliveryRange> blocks = new ArrayList<>();
        YearMonth ym = startYm;
        while (!ym.isAfter(endYm)) {
            blocks.add(DeliveryRange.ofMonth(ym, deliveryTimezone));
            ym = ym.plusMonths(1);
        }
        return List.copyOf(blocks);
    }
}
