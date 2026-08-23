package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for {@code GET /api/da/imbalance/monthly} — monthly imbalance summary.
 *
 * <p>Contains per-day breakdown rows and monthly aggregate totals. Matches
 * {@code DaImbalanceMonthly} in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-04.
 */
public record DaImbalanceMonthlyDto(
    String yearMonth,          // ISO format, e.g. "2026-08"
    String balancingGroupId,
    List<DayRow> dailyBreakdown,
    BigDecimal netImbalanceMwh,
    BigDecimal netImbalanceAmount,
    String currency
) {

    /** Per-day summary within the monthly breakdown. */
    public record DayRow(
        LocalDate day,
        BigDecimal imbalanceMwh,
        BigDecimal imbalanceAmount
    ) {}
}
