package com.power.posval.app.dto.dashboard;

import com.power.posval.domain.port.service.dashboard.DailyAggregate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for L4 month view daily row. Simulator-scope (pv-app).
 * Maps from {@link DailyAggregate} domain record via {@link #from(DailyAggregate)}.
 */
public record DailyAggregateDto(
        Instant dayStart,
        Instant dayEnd,
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
    public static DailyAggregateDto from(DailyAggregate d) {
        return new DailyAggregateDto(
                d.dayStart(),
                d.dayEnd(),
                d.dayStatus(),
                d.intervalCount(),
                d.settledMw(),
                d.settledMwh(),
                d.avgPrice(),
                d.settledValue(),
                d.marketValue(),
                d.realizedPnl(),
                d.forwardMw(),
                d.forwardMwh(),
                d.curvePrice(),
                d.forwardMarkValue(),
                d.currency()
        );
    }
}
