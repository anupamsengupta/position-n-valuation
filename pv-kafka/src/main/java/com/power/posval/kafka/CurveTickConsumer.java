package com.power.posval.kafka;

import jakarta.inject.Inject;

/**
 * Kafka consumer for CurveTick events.
 * Triggers S5b forward mark update.
 */
public class CurveTickConsumer extends IdempotentConsumer<Object> {

    @Inject
    public CurveTickConsumer() {}

    @Override
    protected boolean alreadyProcessed(Object event) {
        return false;
    }

    @Override
    protected void process(Object event) {
        // Trigger S5b forward mark recalculation
    }
}
