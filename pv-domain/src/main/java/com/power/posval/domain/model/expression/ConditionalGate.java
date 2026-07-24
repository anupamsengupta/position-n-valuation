package com.power.posval.domain.model.expression;

/**
 * If gate_input meets condition → override with overrideValue; else → evaluate inner.
 * Negative-price zeroing: if DA < 0 then 0.00 else inner. FR-042a, FR-048b.
 */
public record ConditionalGate(
    PriceExpression gateInput,
    String condition,          // e.g., "< 0"
    PriceExpression overrideValue,
    PriceExpression inner
) implements PriceExpression {}
