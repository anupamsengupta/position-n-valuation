package com.power.posval.domain.command;

import java.time.Instant;

/**
 * Cancels a trade. Forward unwind closes valid_time; void-ab-initio
 * creates CANCELLED version (FR-038).
 * Pattern #17, FR-001–FR-005, S1.
 */
public record TradeCancel(
    String tradeId,
    int tradeVersion,
    String tradeLegId,
    String tenantId,
    String cancellationType,   // FORWARD_UNWIND or VOID_AB_INITIO
    Instant businessEffectiveDate
) {}
