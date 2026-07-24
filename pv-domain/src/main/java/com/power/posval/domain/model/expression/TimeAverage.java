package com.power.posval.domain.model.expression;

/** Average of child over quotation window (e.g., monthly avg of daily fixings). FR-048b. */
public record TimeAverage(
    PriceExpression child,
    String windowSpec          // e.g., "MONTHLY"
) implements PriceExpression {}
