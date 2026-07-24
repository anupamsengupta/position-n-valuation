package com.power.posval.domain.event;

import java.time.Instant;

/**
 * Emitted when a new trade position is captured.
 * Drives downstream volume series creation and slot cache population.
 * Pattern #14, #27, FR-032, S1.
 */
public record PositionCaptured(
    String tenantId,
    String tradeId,
    String tradeLegId,
    int tradeVersion,
    int entryCount,
    Instant eventTime
) {}
