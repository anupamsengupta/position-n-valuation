package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Monthly imbalance settlement aggregate for a single balancing group.
 * Returned by ImbalanceSettlementService.monthlyAggregate().
 * {@code dailyBreakdown} is an unmodifiable ordered list of per-day summaries.
 * Pattern #3, S5.2, DA-SET-04.
 */
public record ImbalanceMonthSummary(
    YearMonth month,
    String balancingGroupId,
    BigDecimal netImbalanceMwh,
    BigDecimal netImbalanceAmount,
    String currency,
    List<ImbalanceDaySummary> dailyBreakdown
) {
    public ImbalanceMonthSummary {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        Objects.requireNonNull(netImbalanceMwh, "netImbalanceMwh");
        Objects.requireNonNull(netImbalanceAmount, "netImbalanceAmount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(dailyBreakdown, "dailyBreakdown");
        if (balancingGroupId.isBlank()) {
            throw new IllegalArgumentException("balancingGroupId must not be blank");
        }
        dailyBreakdown = List.copyOf(dailyBreakdown);
    }
}
