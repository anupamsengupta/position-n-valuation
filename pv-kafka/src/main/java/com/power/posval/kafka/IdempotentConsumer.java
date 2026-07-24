package com.power.posval.kafka;

/**
 * Abstract idempotent Kafka consumer. Pattern #28.
 * Subclasses implement alreadyProcessed() and process().
 * handle() is sealed to enforce idempotency check.
 *
 * @param <E> event type
 */
public abstract class IdempotentConsumer<E> {

    /**
     * Check if this event has already been processed (deduplication).
     * Typically checks a processed_events table or idempotency key store.
     */
    protected abstract boolean alreadyProcessed(E event);

    /**
     * Process the event. Called only if not already processed.
     */
    protected abstract void process(E event);

    /**
     * Handle an incoming event with idempotency guard.
     * This method is final to enforce the deduplication check.
     */
    public final void handle(E event) {
        if (alreadyProcessed(event)) {
            return;
        }
        process(event);
    }
}
