package com.power.posval.domain.port.service.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * L1 portfolio card data — aggregated PnL and volume across all delivery points
 * for a portfolio, grouped by currency.
 *
 * <p>realizedPnl = sum of rollup pnl for settled/transition periods.
 * unrealizedMtm = sum of rollup forwardMarkValue (computed on demand via
 * {@code ForwardMarkService}, per ADR-002, FR-035).
 * totalPortfolioValue = realizedPnl + unrealizedMtm.
 *
 * <p>MW fields are time-weighted averages; MWh fields are sums per FR-035.
 *
 * <p>Pattern #3 (Value Object). D-1, D-3, FR-035, S7.
 */
public record PortfolioSummary(
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
    public PortfolioSummary {
        java.util.Objects.requireNonNull(portfolioId, "portfolioId");
        java.util.Objects.requireNonNull(currency, "currency");
    }
}
