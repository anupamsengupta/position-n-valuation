package com.power.posval.domain.model.expression;

/** Base × ratio. Ratio typically index / base_value. FR-048b. */
public record Escalate(
    PriceExpression base,
    PriceExpression ratio
) implements PriceExpression {}
