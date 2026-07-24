package com.power.posval.domain.model.value;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Delivery-month block range for position ledger entries.
 * Always aligned to calendar-month boundaries in market timezone.
 * Pattern #3, FR-030, S1.
 */
public record DeliveryRange(
    YearMonth startMonth,
    YearMonth endMonth,
    ZoneId deliveryTimezone
) {
    public DeliveryRange {
        Objects.requireNonNull(startMonth, "startMonth");
        Objects.requireNonNull(endMonth, "endMonth");
        Objects.requireNonNull(deliveryTimezone, "deliveryTimezone");
        if (endMonth.isBefore(startMonth)) {
            throw new IllegalArgumentException("endMonth must not be before startMonth");
        }
    }

    public static DeliveryRange ofMonth(YearMonth month, ZoneId zone) {
        return new DeliveryRange(month, month, zone);
    }

    public ZonedDateTime startInstant() {
        return startMonth.atDay(1).atStartOfDay(deliveryTimezone);
    }

    public ZonedDateTime endInstant() {
        return endMonth.plusMonths(1).atDay(1).atStartOfDay(deliveryTimezone);
    }
}
