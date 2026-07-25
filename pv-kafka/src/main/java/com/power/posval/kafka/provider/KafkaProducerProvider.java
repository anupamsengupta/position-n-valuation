package com.power.posval.kafka.provider;

import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Guice provider for KafkaProducer.
 * Creates a producer from system properties.
 */
@Singleton
public class KafkaProducerProvider implements Provider<KafkaProducer<String, String>> {

    private volatile KafkaProducer<String, String> producer;

    @Override
    public KafkaProducer<String, String> get() {
        if (producer == null) {
            synchronized (this) {
                if (producer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        System.getProperty("pv.kafka.bootstrap", "localhost:9092"));
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class.getName());
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                    producer = new KafkaProducer<>(props);
                }
            }
        }
        return producer;
    }
}
