package com.power.posval.domain.port.service;

import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.port.marketdata.MarketDataPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Focused context record passed to {@link InstrumentCalculationStrategy#postSettle}.
 * Carries only what a post-settlement hook needs — no god-object coupling (S9b.3).
 *
 * <p>Pattern #3, S9b.3.
 */
public record PostSettlementContext(
    String tenantId,
    String portfolioId,
    String deliveryPointId,
    LocalDate deliveryDay,
    List<SettlementCell> settledCells,
    MarketDataPort marketDataPort
) {
    public PostSettlementContext {
        Objects.requireNonNull(tenantId,        "tenantId");
        Objects.requireNonNull(portfolioId,     "portfolioId");
        Objects.requireNonNull(deliveryPointId, "deliveryPointId");
        Objects.requireNonNull(deliveryDay,     "deliveryDay");
        settledCells = List.copyOf(Objects.requireNonNull(settledCells, "settledCells"));
        Objects.requireNonNull(marketDataPort,  "marketDataPort");
    }
}
