package com.power.posval.domain.model.expression;

/** FR-048b. */
public record Subtract(PriceExpression left, PriceExpression right) implements PriceExpression {}
