package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for a single interval row in the nomination comparison grid.
 *
 * <p>Shows traded MW (from nomination record's perspective) versus nominated MW
 * and the deviation for one 15-min interval. Matches {@code DaNominationRow}
 * in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-03.
 */
public record DaNominationComparisonRowDto(
    Instant intervalStart,
    Instant intervalEnd,
    String balancingGroupId,
    BigDecimal nominatedVolumeMw,
    int nominationVersion
) {}
