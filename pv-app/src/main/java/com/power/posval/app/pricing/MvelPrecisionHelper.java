package com.power.posval.app.pricing;

import com.power.posval.domain.port.NumericPrecision;

import java.math.BigDecimal;

/**
 * Precision helper injected into the MVEL context as "np".
 * Each method mirrors a tree-walker node's rounding behavior so
 * MVEL formulas produce bit-identical results to the expression-tree evaluator.
 *
 * <pre>
 * Tree node         → Helper method     → Domain
 * ─────────────────────────────────────────────────
 * Divide            → np.divide(a, b)   → INTERMEDIATE (scale 10)
 * Escalate(base×r)  → np.escalate(b, r) → PRICE (scale 8)
 * Multiply          → np.multiply(a, b) → INTERMEDIATE (scale 10)
 * FxConvert(v×rate) → np.monetary(v)    → MONETARY (scale 4)
 * Clamp(min,max,v)  → np.clamp(lo,hi,v) → (no rounding, BigDecimal min/max)
 * </pre>
 */
public class MvelPrecisionHelper {

    private final NumericPrecision precision;

    public MvelPrecisionHelper(NumericPrecision precision) {
        this.precision = precision;
    }

    /** Division at INTERMEDIATE scale — mirrors Divide node. */
    public BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator,
            precision.scale(NumericPrecision.Domain.INTERMEDIATE),
            precision.roundingMode());
    }

    /** base × ratio rounded to PRICE — mirrors Escalate node. */
    public BigDecimal escalate(BigDecimal base, BigDecimal ratio) {
        return precision.round(base.multiply(ratio), NumericPrecision.Domain.PRICE);
    }

    /** a × b rounded to INTERMEDIATE — mirrors Multiply node. */
    public BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return precision.round(left.multiply(right), NumericPrecision.Domain.INTERMEDIATE);
    }

    /** Round to MONETARY — mirrors FxConvert node (val × rate). */
    public BigDecimal monetary(BigDecimal value) {
        return precision.round(value, NumericPrecision.Domain.MONETARY);
    }

    /** Clamp value between min and max — mirrors Clamp node. */
    public BigDecimal clamp(BigDecimal min, BigDecimal max, BigDecimal value) {
        return value.max(min).min(max);
    }

    /** Round to PRICE — mirrors final output rounding. */
    public BigDecimal price(BigDecimal value) {
        return precision.round(value, NumericPrecision.Domain.PRICE);
    }

    /** Round to INTERMEDIATE — scratch-value rounding. */
    public BigDecimal intermediate(BigDecimal value) {
        return precision.round(value, NumericPrecision.Domain.INTERMEDIATE);
    }
}
