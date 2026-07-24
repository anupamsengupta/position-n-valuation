package com.power.posval.domain.model.expression;

/**
 * Reference to a market data series (S4) with quotation parameters.
 * Carries optional settlementSeries for purpose-based resolution (FR-048e).
 * FR-048a.
 */
public record MarketDataLeaf(
    String leafId,
    String series,             // primary series (forward curve)
    String settlementSeries,   // nullable — settlement-specific series (FR-048e)
    int lag,
    String quotationWindow     // SINGLE_INTERVAL, DAILY, MONTHLY
) implements PriceExpression {}
