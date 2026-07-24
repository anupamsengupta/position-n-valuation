package com.power.posval.domain.service;

import com.power.posval.domain.port.NumericPrecision;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Concrete aggregator factories for rollup computation.
 * FR-085: DST-correct time-weighted MW.
 * FR-090: net_mw = time-weighted average; net_mwh = sum.
 * S7.
 */
public final class Aggregators {

    /**
     * Time-weighted average MW.
     * FR-085: weights = interval minutes; mandatory for DST-correct aggregation.
     */
    public static IntervalAggregator<BigDecimal> timeWeightedMw(NumericPrecision np) {
        return intervals -> {
            BigDecimal weightedSum = BigDecimal.ZERO;
            long totalMinutes = 0;
            for (var i : intervals) {
                long minutes = Duration.between(i.intervalStart(), i.intervalEnd())
                    .toMinutes();
                weightedSum = weightedSum.add(
                    i.netMw().multiply(BigDecimal.valueOf(minutes)));
                totalMinutes += minutes;
            }
            return totalMinutes == 0 ? BigDecimal.ZERO
                : weightedSum.divide(BigDecimal.valueOf(totalMinutes),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode());
        };
    }

    /** Sum of MWh. FR-085/FR-090: MWh sums on roll-up. */
    public static IntervalAggregator<BigDecimal> sumMwh() {
        return intervals -> intervals.stream()
            .map(i -> i.netMwh())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Aggregators() {}
}
