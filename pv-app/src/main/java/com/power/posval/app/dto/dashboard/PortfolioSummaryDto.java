package com.power.posval.app.dto.dashboard;

import com.power.posval.domain.port.service.dashboard.PortfolioSummary;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for L1 portfolio card data. Simulator-scope (pv-app).
 * Maps from {@link PortfolioSummary} domain record via {@link #from(PortfolioSummary)}.
 * D-13: no domain types in the DTO field types (only primitives/standard Java).
 */
public record PortfolioSummaryDto(
        String portfolioId,
        String currency,
        BigDecimal realizedPnl,
        BigDecimal unrealizedMtm,
        BigDecimal totalPortfolioValue,
        BigDecimal settledNetMw,
        BigDecimal settledNetMwh,
        BigDecimal forwardNetMw,
        BigDecimal forwardNetMwh,
        Instant dataAsOf
) {
    public static PortfolioSummaryDto from(PortfolioSummary s) {
        return new PortfolioSummaryDto(
                s.portfolioId(),
                s.currency(),
                s.realizedPnl(),
                s.unrealizedMtm(),
                s.totalPortfolioValue(),
                s.settledNetMw(),
                s.settledNetMwh(),
                s.forwardNetMw(),
                s.forwardNetMwh(),
                s.dataAsOf()
        );
    }
}
