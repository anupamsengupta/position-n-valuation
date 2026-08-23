package com.power.posval.domain.port.service;

import com.power.posval.domain.port.marketdata.MarketDataPort;

import java.util.Objects;

/**
 * Focused context record passed to
 * {@link InstrumentCalculationStrategy#referencePriceForInterval}.
 * Carries only what a P&amp;L reference-price lookup needs — no god-object coupling (S9b.3).
 *
 * <p>Pattern #3, S9b.3.
 */
public record PnlContext(
    String tenantId,
    String deliveryPointId,
    MarketDataPort marketDataPort
) {
    public PnlContext {
        Objects.requireNonNull(tenantId,        "tenantId");
        Objects.requireNonNull(deliveryPointId, "deliveryPointId");
        Objects.requireNonNull(marketDataPort,  "marketDataPort");
    }
}
