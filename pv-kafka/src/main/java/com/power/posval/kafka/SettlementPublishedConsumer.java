package com.power.posval.kafka;

import jakarta.inject.Inject;

/**
 * Kafka consumer for SettlementPublished events.
 * Triggers S5a re-computation.
 */
public class SettlementPublishedConsumer extends IdempotentConsumer<Object> {

    @Inject
    public SettlementPublishedConsumer() {}

    @Override
    protected boolean alreadyProcessed(Object event) {
        return false;
    }

    @Override
    protected void process(Object event) {
        // Trigger S5a settlement cell re-computation
    }
}
