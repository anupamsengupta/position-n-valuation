package com.power.posval.domain.port.service.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * L3 trade-level per-position summary.
 *
 * <p>Settled actuals (from S5a settlement cells) are aggregated per FR-035:
 * MW = TWA, MWh = sum, avgPrice = volume-weighted average.
 *
 * <p>Forward forecast (from S6b trade interval cache + {@code ForwardMarkService},
 * per ADR-002): forwardMw = TWA of resolvedQty, forwardMwh = sum of resolvedEnergy,
 * forwardMarkValue = computed by ForwardMarkService (S4 × S6b).
 *
 * <p>deliveryStatus: SETTLED if only S5a data exists; FORWARD if only S6b data
 * exists; PARTIAL if both.
 *
 * <p>Pattern #3 (Value Object). D-1, D-11, FR-035, S1+S5a+S6b.
 */
public record PositionContribution(
        UUID positionId,
        String tradeId,
        String tradeLegId,
        int tradeVersion,
        Instant deliveryStart,
        Instant deliveryEnd,
        BigDecimal quantity,
        String volumeUnit,
        /** Trade direction: "BUY" or "SELL". FR-034, D-1. */
        String direction,
        String deliveryPointId,
        /** SETTLED | PARTIAL | FORWARD */
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
    public PositionContribution {
        java.util.Objects.requireNonNull(positionId, "positionId");
        java.util.Objects.requireNonNull(tradeId, "tradeId");
        java.util.Objects.requireNonNull(tradeLegId, "tradeLegId");
        java.util.Objects.requireNonNull(direction, "direction");
    }
}
