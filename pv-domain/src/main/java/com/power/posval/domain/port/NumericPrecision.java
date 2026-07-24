package com.power.posval.domain.port;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * System-wide numeric scale and precision configuration.
 * Implementations are singletons bound in Guice; overrides per commodity
 * or regulatory regime are supported via named bindings or tenant-scoped providers.
 * <p>
 * FR-036: precision conventions are externalized, not hardcoded.
 * D-12: commodity-neutral core — precision can vary per commodity deployment.
 */
public interface NumericPrecision {

    /** Decimal scale (digits after point) for the given domain. */
    int scale(Domain domain);

    /** Total precision (significant digits) for the given domain. */
    int precision(Domain domain);

    /** Default rounding mode applied system-wide. */
    default RoundingMode roundingMode() {
        return RoundingMode.HALF_UP;
    }

    /** Convenience: apply scale + rounding to a BigDecimal value. */
    default BigDecimal round(BigDecimal value, Domain domain) {
        return value.setScale(scale(domain), roundingMode());
    }

    /**
     * Semantic precision domains.
     * Each domain independently governs scale/precision for a category of numeric values.
     */
    enum Domain {
        /** Currency amounts: settlement values, mark-to-market, invoiced amounts. */
        MONETARY,
        /** Price-per-unit: EUR/MWh, USD/therm, index references. */
        PRICE,
        /** Power capacity / commodity quantity: MW, m³/h. */
        VOLUME,
        /** Energy delivered / commodity energy: MWh, therms. */
        ENERGY,
        /** Allocation ratios, shares, multipliers (0 < m ≤ 1). */
        MULTIPLIER,
        /** Scratch values in multi-step computation; never persisted. */
        INTERMEDIATE
    }
}
