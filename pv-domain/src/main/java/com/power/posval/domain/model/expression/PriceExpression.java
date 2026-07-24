package com.power.posval.domain.model.expression;

/**
 * Sealed expression tree for price resolution. All types immutable records.
 * Fixed price = ConstantLeaf(85.00). Full PPA = nested tree with 6+ leaves.
 * FR-048h, D-2. Pattern #5.
 */
public sealed interface PriceExpression permits
    // Leaf types (terminals) — FR-048a
    ConstantLeaf,
    MarketDataLeaf,
    IndexLeaf,
    // Operator types (non-terminals) — FR-048b
    Add,
    Subtract,
    Multiply,
    Divide,
    Clamp,
    Escalate,
    ConditionalGate,
    ConditionalPassThrough,
    TimeAverage,
    FxConvert { }
