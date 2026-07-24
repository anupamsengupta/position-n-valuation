package com.power.posval.domain.service;

import com.power.posval.domain.model.expression.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.marketdata.MarketDataPort;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.*;

/**
 * Default tree-walker implementation using exhaustive pattern-matching switch.
 * Pattern #5, #10, #12, FR-048h.
 */
public class DefaultPriceEvaluator implements PriceEvaluator {

    private final NumericPrecision np;

    @Inject
    public DefaultPriceEvaluator(NumericPrecision np) {
        this.np = Objects.requireNonNull(np, "np");
    }

    @Override
    public PriceResolution evaluate(
            PriceExpression expr,
            DeliveryPeriod interval,
            ResolutionPurpose purpose,
            MarketDataPort marketData) {

        var activeLeaves = new HashSet<String>();
        var versions = new HashMap<String, Long>();

        BigDecimal result = eval(expr, interval, purpose, marketData, activeLeaves, versions);

        return new PriceResolution(
            np.round(result, NumericPrecision.Domain.PRICE),
            Set.copyOf(activeLeaves),
            Map.copyOf(versions));
    }

    private BigDecimal eval(
            PriceExpression expr,
            DeliveryPeriod interval,
            ResolutionPurpose purpose,
            MarketDataPort md,
            Set<String> activeLeaves,
            Map<String, Long> versions) {

        return switch (expr) {

            case ConstantLeaf c -> {
                activeLeaves.add(c.leafId());
                yield c.value();
            }

            case MarketDataLeaf m -> {
                // Purpose-based resolution (FR-048e)
                String series = (purpose == ResolutionPurpose.SETTLEMENT
                                 && m.settlementSeries() != null)
                                ? m.settlementSeries()
                                : m.series();
                var lookup = md.lookupFixing(series, interval.start().toInstant());
                activeLeaves.add(m.leafId());
                versions.put(series, lookup.versionId());
                yield lookup.value();
            }

            case IndexLeaf i -> {
                var lookup = md.lookupIndex(i.series(), i.refMonthExpression(), interval);
                activeLeaves.add(i.leafId());
                versions.put(i.series(), lookup.versionId());
                yield lookup.value();
            }

            case Clamp cl -> {
                BigDecimal inner = eval(cl.inner(), interval, purpose, md, activeLeaves, versions);
                var boundLeaves = new HashSet<String>();
                BigDecimal min = eval(cl.min(), interval, purpose, md, boundLeaves, versions);
                BigDecimal max = eval(cl.max(), interval, purpose, md, boundLeaves, versions);
                BigDecimal clamped = inner.max(min).min(max);
                if (clamped.compareTo(inner) != 0) {
                    activeLeaves.addAll(boundLeaves);
                }
                yield clamped;
            }

            case Escalate e -> {
                BigDecimal base = eval(e.base(), interval, purpose, md, activeLeaves, versions);
                BigDecimal ratio = eval(e.ratio(), interval, purpose, md, activeLeaves, versions);
                yield np.round(base.multiply(ratio), NumericPrecision.Domain.PRICE);
            }

            case ConditionalGate g -> {
                BigDecimal gateVal = eval(g.gateInput(), interval, purpose, md, activeLeaves, versions);
                if (meetsCondition(gateVal, g.condition())) {
                    yield eval(g.overrideValue(), interval, purpose, md, activeLeaves, versions);
                } else {
                    yield eval(g.inner(), interval, purpose, md, activeLeaves, versions);
                }
            }

            case ConditionalPassThrough pt -> {
                BigDecimal gateVal = eval(pt.gateInput(), interval, purpose, md, activeLeaves, versions);
                if (meetsCondition(gateVal, pt.condition())) {
                    yield gateVal;
                } else {
                    yield eval(pt.inner(), interval, purpose, md, activeLeaves, versions);
                }
            }

            case Add a ->
                eval(a.left(), interval, purpose, md, activeLeaves, versions)
                    .add(eval(a.right(), interval, purpose, md, activeLeaves, versions));

            case Subtract s ->
                eval(s.left(), interval, purpose, md, activeLeaves, versions)
                    .subtract(eval(s.right(), interval, purpose, md, activeLeaves, versions));

            case Multiply mu -> {
                BigDecimal product = eval(mu.left(), interval, purpose, md, activeLeaves, versions)
                    .multiply(eval(mu.right(), interval, purpose, md, activeLeaves, versions));
                yield np.round(product, NumericPrecision.Domain.INTERMEDIATE);
            }

            case Divide d -> {
                yield eval(d.numerator(), interval, purpose, md, activeLeaves, versions)
                    .divide(eval(d.denominator(), interval, purpose, md, activeLeaves, versions),
                            np.scale(NumericPrecision.Domain.INTERMEDIATE), np.roundingMode());
            }

            case TimeAverage ta -> {
                BigDecimal avg = eval(ta.child(), interval, purpose, md, activeLeaves, versions);
                yield avg;
            }

            case FxConvert fx -> {
                BigDecimal val = eval(fx.value(), interval, purpose, md, activeLeaves, versions);
                BigDecimal rate = eval(fx.fxRate(), interval, purpose, md, activeLeaves, versions);
                yield np.round(val.multiply(rate), NumericPrecision.Domain.MONETARY);
            }
        };
    }

    /**
     * Parses simple condition strings like "< 0", "> 100", "<= 50", ">= 0", "== 42".
     */
    private boolean meetsCondition(BigDecimal value, String condition) {
        String trimmed = condition.trim();
        String op;
        String threshold;

        if (trimmed.startsWith("<=")) {
            op = "<=";
            threshold = trimmed.substring(2).trim();
        } else if (trimmed.startsWith(">=")) {
            op = ">=";
            threshold = trimmed.substring(2).trim();
        } else if (trimmed.startsWith("==")) {
            op = "==";
            threshold = trimmed.substring(2).trim();
        } else if (trimmed.startsWith("<")) {
            op = "<";
            threshold = trimmed.substring(1).trim();
        } else if (trimmed.startsWith(">")) {
            op = ">";
            threshold = trimmed.substring(1).trim();
        } else {
            throw new IllegalArgumentException("Unsupported condition: " + condition);
        }

        BigDecimal thresholdValue = new BigDecimal(threshold);
        int cmp = value.compareTo(thresholdValue);

        return switch (op) {
            case "<"  -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">"  -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "==" -> cmp == 0;
            default   -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }
}
