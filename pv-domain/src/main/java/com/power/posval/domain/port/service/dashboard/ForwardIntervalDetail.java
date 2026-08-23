package com.power.posval.domain.port.service.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * L4 forward day view per-interval row.
 *
 * <p>positionId and tradeLegId are nullable for a netted portfolio-level view.
 *
 * <p>evaluatedPrice is computed by {@code ForwardMarkService} (S4 curve + shaping,
 * per ADR-002). markValue = evaluatedPrice × resolvedEnergy.
 *
 * <p>curveId and curveVersion record the curve used at computation time, supporting
 * staleness detection (AC-L1-08, OI-5).
 *
 * <p>Pattern #3 (Value Object). ADR-002, FR-035, S6b, S4.
 */
public record ForwardIntervalDetail(
        Instant intervalStart,
        Instant intervalEnd,
        /** nullable for netted view */
        UUID positionId,
        /** nullable for netted view */
        String tradeLegId,
        BigDecimal resolvedQty,
        BigDecimal resolvedEnergy,
        BigDecimal multiplier,
        String seriesKey,
        BigDecimal evaluatedPrice,
        BigDecimal markValue,
        String curveId,
        long curveVersion,
        String currency
) {
    public ForwardIntervalDetail {
        java.util.Objects.requireNonNull(intervalStart, "intervalStart");
        java.util.Objects.requireNonNull(intervalEnd, "intervalEnd");
    }
}
