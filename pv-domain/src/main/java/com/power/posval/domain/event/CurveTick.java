package com.power.posval.domain.event;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * Market data curve update event. Replaces the {@code Object} placeholder
 * in {@code CurveTickConsumer}. Triggers S5b forward mark recalculation
 * for affected positions via the dependency index.
 * Pattern #26, OI-3.
 */
public record CurveTick(
    String tenantId,
    String series,
    List<YearMonth> affectedPillars,
    Instant observationTime,
    long versionId,
    Instant eventTime
) {}
