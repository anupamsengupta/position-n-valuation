package com.power.posval.domain.service;

/**
 * Pruning policy for dependency edge lifecycle management.
 * FR-104: different rules for different edge classes.
 * S8.
 */
public sealed interface PrunePolicy
    permits SettlementHandoverPolicy, HotStoreRetentionPolicy {
}
