package com.power.posval.persistence.event;

import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.persistence.entity.OutboxEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.StringJoiner;

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
        String payload = serializeToJson(event);

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
        for (String methodName : new String[]{"tradeId", "seriesKey", "positionId"}) {
            try {
                Method method = event.getClass().getMethod(methodName);
                Object result = method.invoke(event);
                return result != null ? result.toString() : eventType(event);
            } catch (Exception ignored) {}
        }
        return eventType(event);
    }

    private String eventType(Object event) {
        return event.getClass().getSimpleName();
    }

    /**
     * Serialize event record to JSON using reflection over record components.
     * Records expose their components via getters matching component names.
     */
    private String serializeToJson(Object event) {
        if (!event.getClass().isRecord()) {
            return "{\"type\":\"" + event.getClass().getSimpleName() + "\"}";
        }

        var components = event.getClass().getRecordComponents();
        var json = new StringJoiner(",", "{", "}");

        for (var component : components) {
            try {
                Object value = component.getAccessor().invoke(event);
                String key = "\"" + component.getName() + "\"";
                if (value == null) {
                    json.add(key + ":null");
                } else if (value instanceof Number n) {
                    json.add(key + ":" + n);
                } else if (value instanceof Boolean b) {
                    json.add(key + ":" + b);
                } else {
                    json.add(key + ":\"" + escapeJson(value.toString()) + "\"");
                }
            } catch (Exception ignored) {
                // Skip inaccessible components
            }
        }

        return json.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
