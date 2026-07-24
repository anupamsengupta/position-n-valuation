package com.power.posval.domain.service;

import java.time.Instant;

/**
 * Prune forward-curve edges when interval settles final.
 * FR-104: forward-curve edges drop on settlement handover.
 */
public record SettlementHandoverPolicy(
    Instant settlementCutoff
) implements PrunePolicy {}
