package com.power.posval.domain.port.event;

/**
 * Port for publishing domain events. Pattern #14, #27, S3/S5a/S8.
 * Implementation in pv-kafka writes to outbox table (Pattern #24).
 */
public interface DomainEventPublisher {

    /** Publish a domain event. The event type is inferred at runtime. */
    void publish(Object event);
}
