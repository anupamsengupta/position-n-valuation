package com.power.posval.app.dto;

import com.power.posval.domain.model.PositionMonthSummary;

import java.math.BigDecimal;

public record PositionMonthSummaryDto(
        String positionId,
        String tenantId,
        String tradeId,
        String tradeLegId,
        String deliveryMonth,
        BigDecimal totalMwh,
        BigDecimal avgMw,
        BigDecimal totalAmount,
        BigDecimal totalMarketAmount,
        BigDecimal totalPnl,
        BigDecimal avgPrice,
        BigDecimal avgMarketPrice,
        String currency,
        int cellCount
) {
    public static PositionMonthSummaryDto from(PositionMonthSummary s) {
        return new PositionMonthSummaryDto(
                s.positionId().toString(),
                s.tenantId(),
                s.tradeId(),
                s.tradeLegId(),
                s.deliveryMonth().toString(),
                s.totalMwh(),
                s.avgMw(),
                s.totalAmount(),
                s.totalMarketAmount(),
                s.totalPnl(),
                s.avgPrice(),
                s.avgMarketPrice(),
                s.currency(),
                s.cellCount()
        );
    }
}
