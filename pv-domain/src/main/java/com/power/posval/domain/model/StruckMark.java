package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

/**
 * Domain model for an EOD struck mark (S5c).
 * Immutable, append-only. FR-077, FR-078, Pattern #1, #34.
 */
public record StruckMark(
    String tenantId,
    UUID positionId,
    YearMonth deliveryMonth,
    LocalDate strikeDate,
    BigDecimal markValue,
    String currency,
    Map<String, Long> curveVersionSet,
    String fxVersion,
    Map<String, Long> volumeVersionSet,
    long expressionVersion,
    Long supersedesId,
    boolean isRestrike,
    Instant createdAt
) {
    public StruckMark {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(positionId, "positionId");
        java.util.Objects.requireNonNull(deliveryMonth, "deliveryMonth");
        java.util.Objects.requireNonNull(strikeDate, "strikeDate");
        java.util.Objects.requireNonNull(markValue, "markValue");
        java.util.Objects.requireNonNull(currency, "currency");
        java.util.Objects.requireNonNull(curveVersionSet, "curveVersionSet");
    }
}
