package com.power.posval.app.dto.dashboard;

import com.power.posval.domain.port.service.dashboard.ForwardIntervalDetail;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for L4 forward day view per-interval row. Simulator-scope (pv-app).
 * Maps from {@link ForwardIntervalDetail} domain record via {@link #from(ForwardIntervalDetail)}.
 *
 * <p>EMIR labeling: evaluatedPrice and markValue are <em>indicative current marks</em>
 * computed on demand from S4 curves × S6b volumes (ADR-002), NOT official EOD marks
 * (S5c). S11 regulatory impact.
 */
public record ForwardIntervalDetailDto(
        Instant intervalStart,
        Instant intervalEnd,
        String positionId,
        String tradeLegId,
        BigDecimal resolvedQty,
        BigDecimal resolvedEnergy,
        BigDecimal multiplier,
        String seriesKey,
        BigDecimal evaluatedPrice,
        BigDecimal markValue,
        String curveId,
        long curveVersion,
        String currency,
        /** EMIR S11: "indicative" mark, not an official EOD snapshot. */
        String markType
) {
    public static ForwardIntervalDetailDto from(ForwardIntervalDetail d) {
        return new ForwardIntervalDetailDto(
                d.intervalStart(),
                d.intervalEnd(),
                d.positionId() != null ? d.positionId().toString() : null,
                d.tradeLegId(),
                d.resolvedQty(),
                d.resolvedEnergy(),
                d.multiplier(),
                d.seriesKey(),
                d.evaluatedPrice(),
                d.markValue(),
                d.curveId(),
                d.curveVersion(),
                d.currency(),
                "INDICATIVE"  // S11 EMIR labeling requirement
        );
    }
}
