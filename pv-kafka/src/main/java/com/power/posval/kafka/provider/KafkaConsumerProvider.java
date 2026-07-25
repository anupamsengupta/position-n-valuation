package com.power.posval.kafka.provider;

import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.util.Properties;

/**
 * Guice provider for KafkaConsumer.
 * Creates a consumer from system properties.
 */
@Singleton
public class KafkaConsumerProvider implements Provider<KafkaConsumer<String, String>> {

    @Override
    public KafkaConsumer<String, String> get() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            System.getProperty("pv.kafka.bootstrap", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
            System.getProperty("pv.kafka.group.id", "pv-consumer-group"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }
}
