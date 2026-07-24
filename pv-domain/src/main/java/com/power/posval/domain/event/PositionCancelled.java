package com.power.posval.domain.event;

import java.time.Instant;

/**
 * Emitted when a trade position is cancelled.
 * Drives cleanup of volume references, forward marks, and cache entries.
 * Pattern #14, #27, FR-038, S1.
 */
public record PositionCancelled(
    String tenantId,
    String tradeId,
    String tradeLegId,
    int tradeVersion,
    String cancellationType,
    Instant eventTime
) {}
