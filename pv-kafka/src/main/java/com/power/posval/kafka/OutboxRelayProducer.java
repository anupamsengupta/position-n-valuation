package com.power.posval.kafka;

import com.power.posval.persistence.entity.OutboxEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Instant;
import java.util.List;

/**
 * Outbox relay producer. §15.2, Pattern #24.
 * Polls trade.outbox for unpublished rows, produces to Kafka,
 * marks published_at after broker ACK.
 */
public class OutboxRelayProducer {

    private final Provider<EntityManager> emProvider;
    private final KafkaProducer<String, String> producer;
    private final String topicPrefix;

    @Inject
    public OutboxRelayProducer(Provider<EntityManager> emProvider,
                                KafkaProducer<String, String> producer) {
        this.emProvider = emProvider;
        this.producer = producer;
        this.topicPrefix = "posval.";
    }

    /**
     * Poll for unpublished outbox rows and relay to Kafka.
     * Called on a schedule (every 100ms) or on-demand.
     */
    public int relay() {
        EntityManager em = emProvider.get();
        List<OutboxEntity> unpublished = em.createQuery("""
            SELECT o FROM OutboxEntity o
            WHERE o.publishedAt IS NULL
            ORDER BY o.createdAt ASC
            """, OutboxEntity.class)
            .setMaxResults(100)
            .getResultList();

        int published = 0;
        for (OutboxEntity entry : unpublished) {
            try {
                String topic = topicPrefix + entry.getEventType();
                var record = new ProducerRecord<>(topic,
                    entry.getAggregateId(), entry.getPayload());

                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        entry.setPublishedAt(Instant.now());
                        em.merge(entry);
                    } else {
                        entry.setPublishAttempts(entry.getPublishAttempts() + 1);
                        em.merge(entry);
                    }
                });
                published++;
            } catch (Exception e) {
                entry.setPublishAttempts(entry.getPublishAttempts() + 1);
                em.merge(entry);
            }
        }
        return published;
    }
}
