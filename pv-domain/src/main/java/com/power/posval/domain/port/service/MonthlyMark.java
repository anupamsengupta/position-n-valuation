package com.power.posval.domain.port.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Forward MtM for a position-month, computed on demand by {@code ForwardMarkService}.
 *
 * <p>forwardMtm = sum of (evaluatedPrice × resolvedEnergy) for all intervals
 * in the month. totalMwh = sum of resolvedEnergy. avgPrice = forwardMtm / totalMwh
 * (volume-weighted).
 *
 * <p>curveId and curveVersion record the S4 curve used at computation time,
 * supporting staleness detection (OI-5, AC-L1-08).
 *
 * <p>ADR-002, Pattern #3 (Value Object).
 */
public record MonthlyMark(
        UUID positionId,
        Instant monthStart,
        Instant monthEnd,
        BigDecimal forwardMtm,
        BigDecimal totalMwh,
        BigDecimal avgPrice,
        String curveId,
        long curveVersion,
        long volumeVersion,
        String currency
) {
    public MonthlyMark {
        java.util.Objects.requireNonNull(positionId, "positionId");
        java.util.Objects.requireNonNull(monthStart, "monthStart");
        java.util.Objects.requireNonNull(monthEnd, "monthEnd");
        java.util.Objects.requireNonNull(currency, "currency");
    }
}
