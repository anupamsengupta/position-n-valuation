package com.power.posval.app.pricing;

import com.ctrm.ruleengine.api.RuleContext;
import com.ctrm.ruleengine.api.RuleEngine;
import com.ctrm.ruleengine.api.RuleResultContext;
import com.ctrm.ruleengine.api.WorkflowPoint;
import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.service.PriceEvaluator;
import com.power.posval.domain.service.PriceResolution;
import com.power.posval.domain.service.ResolutionPurpose;

import java.math.BigDecimal;
import java.util.*;

/**
 * PriceEvaluator backed by the rule-engine-4x MVEL evaluator.
 * Pre-resolves market data into the RuleContext as BigDecimal, injects
 * a {@link MvelPrecisionHelper} as "np" for intermediate rounding, then
 * delegates to the engine.
 */
public class RuleEngineBasedEvaluator implements PriceEvaluator {

    private final RuleEngine ruleEngine;
    private final Map<PriceExpression, String> expressionToId;
    private final Map<String, PriceRuleDefinition> definitionsByExprId;
    private final NumericPrecision np;
    private final MvelPrecisionHelper precisionHelper;

    public RuleEngineBasedEvaluator(
            RuleEngine ruleEngine,
            Map<PriceExpression, String> expressionToId,
            Map<String, PriceRuleDefinition> definitionsByExprId,
            NumericPrecision np) {
        this.ruleEngine = ruleEngine;
        this.expressionToId = expressionToId;
        this.definitionsByExprId = definitionsByExprId;
        this.np = np;
        this.precisionHelper = new MvelPrecisionHelper(np);
    }

    @Override
    public PriceResolution evaluate(
            PriceExpression expression,
            DeliveryPeriod interval,
            ResolutionPurpose purpose,
            MarketDataPort marketData) {

        // 1. Reverse-lookup expression ID
        String exprId = expressionToId.get(expression);
        if (exprId == null) {
            throw new IllegalArgumentException(
                "No rule definition found for expression: " + expression);
        }

        // 2. Load definition
        PriceRuleDefinition def = definitionsByExprId.get(exprId);
        if (def == null) {
            throw new IllegalArgumentException(
                "No PriceRuleDefinition for exprId: " + exprId);
        }

        // 3. Pre-resolve market data and build context bindings
        var inputVersionSet = new HashMap<String, Long>();
        var contextBuilder = RuleContext.builder()
            .tenant("default")
            .put("exprId", exprId)
            .put("np", precisionHelper);

        // Inject constants as BigDecimal for precise arithmetic
        for (var entry : def.constants().entrySet()) {
            contextBuilder.put(entry.getKey(), entry.getValue());
        }

        // Resolve series bindings — values injected as BigDecimal
        for (var entry : def.seriesBindings().entrySet()) {
            String varName = entry.getKey();
            PriceRuleDefinition.SeriesBinding binding = entry.getValue();

            // Purpose-based series selection (FR-048e)
            String series = (purpose == ResolutionPurpose.SETTLEMENT
                             && binding.settlementSeries() != null)
                            ? binding.settlementSeries()
                            : binding.series();

            MarketDataLookup lookup = switch (binding.lookupType()) {
                case FIXING -> marketData.lookupFixing(series, interval.start().toInstant());
                case INDEX -> marketData.lookupIndex(
                    binding.series(), binding.refMonthExpression(), interval);
                case FX_RATE -> marketData.lookupFxRate(series, interval.start().toInstant());
            };

            inputVersionSet.put(
                binding.lookupType() == PriceRuleDefinition.LookupType.INDEX
                    ? binding.series() : series,
                lookup.versionId());
            contextBuilder.put(varName, lookup.value());
        }

        RuleContext context = contextBuilder.build();

        // 4. Evaluate via rule engine
        RuleResultContext result = ruleEngine.apply(WorkflowPoint.SETTLEMENT, context);

        // 5. Extract result
        BigDecimal price = result.decimal("price");
        if (price == null) {
            throw new IllegalStateException(
                "Rule engine returned no 'price' slot for exprId: " + exprId
                + ", status: " + result.status());
        }

        return new PriceResolution(
            np.round(price, NumericPrecision.Domain.PRICE),
            Set.of(),
            Map.copyOf(inputVersionSet));
    }
}
