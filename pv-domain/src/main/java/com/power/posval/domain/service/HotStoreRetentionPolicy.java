package com.power.posval.domain.service;

import java.time.YearMonth;

/**
 * Prune settlement-input edges when delivery month leaves hot store.
 * FR-104: persist until retention window expires.
 */
public record HotStoreRetentionPolicy(
    YearMonth oldestRetainedMonth
) implements PrunePolicy {}
