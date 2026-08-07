package com.power.posval.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.power.posval.domain.event.PositionEntryCaptured;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.service.SettlementMaterializationJob;
import com.power.posval.app.provider.SpringEntityManagerProvider;
import com.power.posval.kafka.OutboxRelayProducer;
import com.power.posval.kafka.TradeCapturedConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${pv.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${pv.kafka.consumer.max-poll-records:50}")
    private int maxPollRecords;

    @Value("${pv.kafka.consumer.concurrency:1}")
    private int concurrency;

    // ── Producer (unchanged — used by OutboxRelayProducer) ──────────────

    @Bean(destroyMethod = "close")
    public KafkaProducer<String, String> kafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        return new KafkaProducer<>(props);
    }

    // ── Spring KafkaTemplate (for DLQ publishing) ──────────────────────

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    // ── PositionEntryCaptured consumer factory (typed deserializer) ────

    @Bean
    public ConsumerFactory<String, PositionEntryCaptured> positionEntryCapturedConsumerFactory(ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "pv-settlement-consumer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);

        // ErrorHandlingDeserializer wraps the real deserializer so that
        // deserialization failures are routed to the error handler → DLQ
        // instead of being retried forever.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // JsonDeserializer config — trust our event package
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.power.posval.domain.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, PositionEntryCaptured.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ── Container factory with error handler + DLQ ────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PositionEntryCaptured>
            positionEntryCapturedListenerFactory(
                    ConsumerFactory<String, PositionEntryCaptured> positionEntryCapturedConsumerFactory,
                    KafkaTemplate<String, String> kafkaTemplate) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, PositionEntryCaptured>();
        factory.setConsumerFactory(positionEntryCapturedConsumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Exponential backoff: 1s → 2s → 4s, max 3 retries
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(3);

        // DLQ: failed records go to <original-topic>.DLQ
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, ex) -> new org.apache.kafka.common.TopicPartition(
                                record.topic() + ".DLQ", record.partition()));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Deserialization errors are not retryable — send straight to DLQ
        errorHandler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class);

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    // ── Domain beans ──────────────────────────────────────────────────

    @Bean
    public OutboxRelayProducer outboxRelayProducer(SpringEntityManagerProvider emProvider,
                                                    KafkaProducer<String, String> kafkaProducer) {
        return new OutboxRelayProducer(emProvider, kafkaProducer);
    }

    @Bean
    public TradeCapturedConsumer tradeCapturedConsumer(PositionLedgerRepository ledgerRepo,
                                                       SettlementCellRepository cellRepo,
                                                       SettlementMaterializationJob settlementJob) {
        return new TradeCapturedConsumer(ledgerRepo, cellRepo, settlementJob);
    }
}
