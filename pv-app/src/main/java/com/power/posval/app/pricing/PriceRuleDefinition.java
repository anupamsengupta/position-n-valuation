package com.power.posval.app.pricing;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Metadata mapping a price expression ID to MVEL formula constants and series bindings.
 * Loaded from stub/price-rule-definitions.json at startup.
 */
public record PriceRuleDefinition(
    String priceExpressionId,
    String mvelFormula,
    Map<String, BigDecimal> constants,
    Map<String, SeriesBinding> seriesBindings
) {

    public record SeriesBinding(
        String series,
        String settlementSeries,
        LookupType lookupType,
        String refMonthExpression
    ) {}

    public enum LookupType {
        FIXING, INDEX, FX_RATE
    }
}
