package com.power.posval.domain.model.expression;

/** FR-048b. */
public record Divide(PriceExpression numerator, PriceExpression denominator)
    implements PriceExpression {}
