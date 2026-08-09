package com.power.posval.app.dto;

import com.power.posval.domain.port.repository.RollupCell;

import java.math.BigDecimal;
import java.time.Instant;

public record RollupCellDto(
        Instant periodStart,
        Instant periodEnd,
        String granularity,
        String deliveryPointId,
        String portfolioId,
        boolean isPeak,
        BigDecimal netMw,
        BigDecimal netMwh,
        BigDecimal settledValue,
        BigDecimal marketValue,
        BigDecimal pnl,
        BigDecimal forwardMarkValue,
        String currency,
        String calendarVersion,
        String versionHash
) {
    public static RollupCellDto from(RollupCell c) {
        return new RollupCellDto(
                c.periodStart(),
                c.periodEnd(),
                c.granularity().name(),
                c.deliveryPointId(),
                c.portfolioId(),
                c.isPeak(),
                c.netMw(),
                c.netMwh(),
                c.settledValue(),
                c.marketValue(),
                c.pnl(),
                c.forwardMarkValue(),
                c.currency(),
                c.calendarVersion(),
                c.versionHash()
        );
    }
}
