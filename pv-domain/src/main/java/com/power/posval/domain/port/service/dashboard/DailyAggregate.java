package com.power.posval.domain.port.service.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * L4 month view daily row.
 *
 * <p>dayStart and dayEnd are UTC instants representing the correct CET/CEST
 * day boundaries per S10c DST handling conventions.
 *
 * <p>intervalCount is 92 (spring-forward), 96 (normal), or 100 (fall-back).
 *
 * <p>Settled fields are null for FORWARD days; forward fields are null for
 * SETTLED days.
 *
 * <p>Pattern #3 (Value Object). FR-035, S7, S5a, ADR-002.
 */
public record DailyAggregate(
        Instant dayStart,
        Instant dayEnd,
        /** SETTLED | TODAY | FORWARD */
        String dayStatus,
        int intervalCount,

        // Settled data (null for FORWARD days)
        BigDecimal settledMw,
        BigDecimal settledMwh,
        BigDecimal avgPrice,
        BigDecimal settledValue,
        BigDecimal marketValue,
        BigDecimal realizedPnl,

        // Forward data (null for SETTLED days)
        BigDecimal forwardMw,
        BigDecimal forwardMwh,
        BigDecimal curvePrice,
        BigDecimal forwardMarkValue,

        String currency
) {
    public DailyAggregate {
        java.util.Objects.requireNonNull(dayStart, "dayStart");
        java.util.Objects.requireNonNull(dayEnd, "dayEnd");
        java.util.Objects.requireNonNull(dayStatus, "dayStatus");
    }
}
