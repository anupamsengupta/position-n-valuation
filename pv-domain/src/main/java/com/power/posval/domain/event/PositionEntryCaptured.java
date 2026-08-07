package com.power.posval.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted once per PositionLedgerEntry (monthly block) when a trade is captured.
 * Each event carries the positionId so the consumer can load a single entry
 * without re-querying by trade/leg/version.
 * Pattern #14, #27, FR-032, S1.
 */
public record PositionEntryCaptured(
    String tenantId,
    UUID positionId,
    Instant eventTime
) {}
