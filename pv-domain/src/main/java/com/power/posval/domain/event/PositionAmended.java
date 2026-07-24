package com.power.posval.domain.event;

import java.time.Instant;

/**
 * Emitted when a trade position is amended.
 * Drives re-resolution of volume series, slot cache invalidation, revaluation.
 * Pattern #14, #27, FR-037, S1.
 */
public record PositionAmended(
    String tenantId,
    String tradeId,
    String tradeLegId,
    int tradeVersion,
    String amendmentReason,
    Instant eventTime
) {}
