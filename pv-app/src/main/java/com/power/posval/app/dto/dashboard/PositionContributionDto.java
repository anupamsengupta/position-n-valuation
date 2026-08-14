package com.power.posval.app.dto.dashboard;

import com.power.posval.domain.port.service.dashboard.PositionContribution;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for L3 trade-level per-position summary. Simulator-scope (pv-app).
 * Maps from {@link PositionContribution} domain record via {@link #from(PositionContribution)}.
 */
public record PositionContributionDto(
        String positionId,
        String tradeId,
        String tradeLegId,
        int tradeVersion,
        Instant deliveryStart,
        Instant deliveryEnd,
        BigDecimal quantity,
        String volumeUnit,
        String deliveryPointId,
        String deliveryStatus,

        // Settled actuals (from S5a)
        BigDecimal settledMw,
        BigDecimal settledMwh,
        BigDecimal avgPrice,
        BigDecimal settledValue,
        BigDecimal marketValue,
        BigDecimal realizedPnl,

        // Forward forecast (from S6b + ForwardMarkService, per ADR-002)
        BigDecimal forwardMw,
        BigDecimal forwardMwh,
        BigDecimal forwardMarkValue,
        BigDecimal unrealizedMtm,

        String currency
) {
    public static PositionContributionDto from(PositionContribution c) {
        return new PositionContributionDto(
                c.positionId() != null ? c.positionId().toString() : null,
                c.tradeId(),
                c.tradeLegId(),
                c.tradeVersion(),
                c.deliveryStart(),
                c.deliveryEnd(),
                c.quantity(),
                c.volumeUnit(),
                c.deliveryPointId(),
                c.deliveryStatus(),
                c.settledMw(),
                c.settledMwh(),
                c.avgPrice(),
                c.settledValue(),
                c.marketValue(),
                c.realizedPnl(),
                c.forwardMw(),
                c.forwardMwh(),
                c.forwardMarkValue(),
                c.unrealizedMtm(),
                c.currency()
        );
    }
}
