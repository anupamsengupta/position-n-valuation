package com.power.posval.kafka;

import com.power.posval.domain.event.PositionCaptured;
import jakarta.inject.Inject;

/**
 * Kafka consumer for PositionCaptured events.
 * Triggers S3/S5/S6 cascade.
 */
public class TradeCapturedConsumer extends IdempotentConsumer<PositionCaptured> {

    @Inject
    public TradeCapturedConsumer() {}

    @Override
    protected boolean alreadyProcessed(PositionCaptured event) {
        // Check processed_events table by tradeId + tradeVersion
        return false;
    }

    @Override
    protected void process(PositionCaptured event) {
        // Trigger downstream: volume materialization (S3),
        // settlement computation (S5), cache population (S6)
    }
}
