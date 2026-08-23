package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for {@code GET /api/da/nominations} — nomination comparison grid for a delivery day.
 *
 * <p>Contains per-interval nomination rows plus summary totals. Matches
 * {@code DaNominationGrid} in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-03.
 */
public record DaNominationGridDto(
    LocalDate deliveryDay,
    String balancingGroupId,   // null when aggregated across all groups
    List<DaNominationComparisonRowDto> rows,
    BigDecimal totalNominatedMwh,
    int intervalCount
) {}
