package com.power.posval.kafka;

import com.power.posval.domain.port.repository.DependencyIndex;
import com.power.posval.domain.service.ForwardMarkJob;
import jakarta.inject.Inject;

/**
 * Kafka consumer for CurveTick events (market data curve updates).
 * Triggers S5b forward mark recalculation. Pattern #26.
 *
 * Uses Object event type pending CurveTick domain event definition
 * (depends on MarketDataPort contract — OI-3).
 */
public class CurveTickConsumer extends IdempotentConsumer<Object> {

    private final DependencyIndex dependencyIndex;
    private final ForwardMarkJob forwardMarkJob;

    @Inject
    public CurveTickConsumer(DependencyIndex dependencyIndex,
                              ForwardMarkJob forwardMarkJob) {
        this.dependencyIndex = dependencyIndex;
        this.forwardMarkJob = forwardMarkJob;
    }

    @Override
    protected boolean alreadyProcessed(Object event) {
        // D-7: forward mark recalculation is idempotent (overwrites current state).
        return false;
    }

    @Override
    protected void process(Object event) {
        // CurveTick event structure TBD — once defined, extract series key and
        // affected range, then query dependency index for affected cells and
        // re-execute ForwardMarkJob for each affected position.
        //
        // Placeholder: when CurveTick domain event is defined, implement:
        // 1. var affected = dependencyIndex.findAffectedCells(tenantId, seriesKey, range, null);
        // 2. For each affected cell, load position and call forwardMarkJob.execute(position, range);
    }
}
