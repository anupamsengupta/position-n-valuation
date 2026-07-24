package com.power.posval.domain.model.expression;

/** Floor/cap/collar: max(min, min(max, inner)). FR-048b. */
public record Clamp(
    PriceExpression min,
    PriceExpression max,
    PriceExpression inner
) implements PriceExpression {}
