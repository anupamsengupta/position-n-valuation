package com.power.posval.domain.model.expression;

import java.math.BigDecimal;

/**
 * Fixed numeric value with unit. Always active unless pruned by ancestor gate.
 * Example: 85.00 EUR/MWh (fixed price), 42.00 (collar floor base), 105.2 (CPI base).
 * FR-048a.
 */
public record ConstantLeaf(
    String leafId,
    BigDecimal value,
    String unit
) implements PriceExpression {}
