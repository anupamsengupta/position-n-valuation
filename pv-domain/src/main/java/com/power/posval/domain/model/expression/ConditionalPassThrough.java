package com.power.posval.domain.model.expression;

/**
 * If gate_input meets condition → pass gate_input as result; else → evaluate inner.
 * Negative-price pass-through: if DA < 0 then DA else inner. FR-042a.
 */
public record ConditionalPassThrough(
    PriceExpression gateInput,
    String condition,
    PriceExpression inner
) implements PriceExpression {}
