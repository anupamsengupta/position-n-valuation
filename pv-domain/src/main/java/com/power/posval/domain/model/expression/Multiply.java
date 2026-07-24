package com.power.posval.domain.model.expression;

/** FR-048b. */
public record Multiply(PriceExpression left, PriceExpression right) implements PriceExpression {}
