package com.power.posval.domain.service;

import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.marketdata.MarketDataPort;

/**
 * Port interface for price expression evaluation. Lives in pv-domain.
 * Implementation dispatches via pattern-matching switch over the sealed hierarchy.
 * Pattern #10, FR-045, S2.
 */
@FunctionalInterface
public interface PriceEvaluator {

    /**
     * Evaluate expression tree for a single interval.
     * @param expression  the expression tree (sealed hierarchy)
     * @param interval    the delivery interval being priced
     * @param purpose     FORWARD or SETTLEMENT (drives leaf resolution per FR-048e)
     * @param marketData  port to look up market data at correct version
     * @return resolution with value, active_leaves, and input_version_set
     */
    PriceResolution evaluate(
        PriceExpression expression,
        DeliveryPeriod interval,
        ResolutionPurpose purpose,
        MarketDataPort marketData
    );
}
