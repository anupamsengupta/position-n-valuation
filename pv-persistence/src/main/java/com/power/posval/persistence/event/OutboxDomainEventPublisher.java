package com.power.posval.persistence.event;

import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.persistence.entity.OutboxEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;

/**
 * Outbox-based implementation of DomainEventPublisher.
 * Writes events to trade.outbox table in the same transaction as domain mutation.
 * Pattern #24, §15.2.
 */
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final Provider<EntityManager> emProvider;

    @Inject
    public OutboxDomainEventPublisher(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void publish(Object event) {
        EntityManager em = emProvider.get();

        String eventType = event.getClass().getSimpleName();
        String aggregateType = inferAggregateType(event);
        String aggregateId = inferAggregateId(event);
        String payload = serializeEvent(event);

        OutboxEntity entry = new OutboxEntity();
        entry.setAggregateType(aggregateType);
        entry.setAggregateId(aggregateId);
        entry.setEventType(eventType);
        entry.setPayload(payload);
        entry.setCreatedAt(Instant.now());

        em.persist(entry);
    }

    private String inferAggregateType(Object event) {
        String name = event.getClass().getSimpleName();
        if (name.startsWith("Position")) return "PositionLedger";
        if (name.startsWith("Volume")) return "VolumeSeries";
        if (name.startsWith("Settlement")) return "Settlement";
        return "Domain";
    }

    private String inferAggregateId(Object event) {
        // Extract aggregate ID via reflection or known record accessors
        try {
            var method = event.getClass().getMethod("tradeId");
            return (String) method.invoke(event);
        } catch (Exception e) {
            return event.getClass().getSimpleName();
        }
    }

    private String serializeEvent(Object event) {
        // Simple JSON serialization via record toString for now.
        // Production would use Jackson ObjectMapper.
        return event.toString();
    }
}
