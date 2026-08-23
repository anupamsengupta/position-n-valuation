package com.power.posval.domain.port.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Forward MtM for a single interval, computed on demand by {@code ForwardMarkService}.
 *
 * <p>resolvedQty is from S6b in MW. resolvedEnergy is in MWh.
 * evaluatedPrice is from S4 curve + price expression evaluation + hourly shaping.
 * markValue = evaluatedPrice × resolvedEnergy.
 *
 * <p>curveId and curveVersion record the S4 curve used at computation time
 * for staleness detection (OI-5, AC-L1-08).
 *
 * <p>ADR-002, Pattern #3 (Value Object).
 */
public record IntervalMark(
        Instant intervalStart,
        Instant intervalEnd,
        UUID positionId,
        String tradeLegId,
        BigDecimal resolvedQty,
        BigDecimal resolvedEnergy,
        BigDecimal evaluatedPrice,
        BigDecimal markValue,
        String curveId,
        long curveVersion,
        String currency
) {
    public IntervalMark {
        java.util.Objects.requireNonNull(intervalStart, "intervalStart");
        java.util.Objects.requireNonNull(intervalEnd, "intervalEnd");
        java.util.Objects.requireNonNull(positionId, "positionId");
        java.util.Objects.requireNonNull(currency, "currency");
    }
}
