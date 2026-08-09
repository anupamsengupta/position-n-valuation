package com.power.posval.persistence.event;

import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.persistence.entity.OutboxEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
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
     * Handles nested records, collections, maps, and standard value types.
     */
    private String serializeToJson(Object event) {
        return toJsonValue(event);
    }

    private String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value.getClass().isRecord()) {
            return recordToJson(value);
        }
        if (value instanceof Map<?, ?> map) {
            return mapToJson(map);
        }
        if (value instanceof Collection<?> coll) {
            return collectionToJson(coll);
        }
        // String, UUID, Instant, ZonedDateTime, Currency, enums — all quote-wrapped
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private String recordToJson(Object record) {
        var components = record.getClass().getRecordComponents();
        var json = new StringJoiner(",", "{", "}");
        for (var component : components) {
            try {
                Object fieldValue = component.getAccessor().invoke(record);
                json.add("\"" + component.getName() + "\":" + toJsonValue(fieldValue));
            } catch (Exception ignored) {
                // Skip inaccessible components
            }
        }
        return json.toString();
    }

    private String mapToJson(Map<?, ?> map) {
        var json = new StringJoiner(",", "{", "}");
        for (var entry : map.entrySet()) {
            String key = "\"" + escapeJson(entry.getKey().toString()) + "\"";
            json.add(key + ":" + toJsonValue(entry.getValue()));
        }
        return json.toString();
    }

    private String collectionToJson(Collection<?> coll) {
        var json = new StringJoiner(",", "[", "]");
        for (Object item : coll) {
            json.add(toJsonValue(item));
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
