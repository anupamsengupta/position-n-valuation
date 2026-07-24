package com.power.posval.domain.model.expression;

/** Child × FX rate for cross-currency deals. FR-048b. */
public record FxConvert(
    PriceExpression value,
    PriceExpression fxRate
) implements PriceExpression {}
