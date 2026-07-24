package com.power.posval.domain.model.expression;

/**
 * Reference to macro index with reference-month mapping.
 * Example: {series: "HICP-DE", refMonth: deliveryYear-1 + November}.
 * FR-048a.
 */
public record IndexLeaf(
    String leafId,
    String series,
    String refMonthExpression  // e.g., "deliveryYear-1:November"
) implements PriceExpression {}
