package com.power.posval.domain.model.expression;

/** FR-048b. */
public record Add(PriceExpression left, PriceExpression right) implements PriceExpression {}
