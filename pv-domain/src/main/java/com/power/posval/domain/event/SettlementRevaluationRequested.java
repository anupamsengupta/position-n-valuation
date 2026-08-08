package com.power.posval.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain event requesting settlement revaluation for a specific
 * position over a sub-month interval range. Published by MarketDataUpdated
 * and VolumeSuperseded consumers after identifying affected positions.
 * The revaluation service will delete-then-insert settlement cells for
 * the affected interval range on the latest position ledger entry.
 */
public record SettlementRevaluationRequested(
    String tenantId,
    UUID positionId,
    Instant intervalStart,   // half-open [start, end)
    Instant intervalEnd,
    String triggerType,      // "MARKET_DATA" or "VOLUME_SUPERSEDED"
    Instant eventTime
) {}
