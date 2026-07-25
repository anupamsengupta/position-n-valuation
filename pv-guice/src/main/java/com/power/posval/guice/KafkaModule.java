package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.power.posval.kafka.*;
import com.power.posval.kafka.provider.KafkaConsumerProvider;
import com.power.posval.kafka.provider.KafkaProducerProvider;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;

/**
 * Guice module for Kafka infrastructure. §16.6.
 * Binds outbox relay, consumers, and producer/consumer providers.
 */
public class KafkaModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(new TypeLiteral<KafkaProducer<String, String>>() {})
            .toProvider(KafkaProducerProvider.class)
            .in(Singleton.class);

        bind(new TypeLiteral<KafkaConsumer<String, String>>() {})
            .toProvider(KafkaConsumerProvider.class);

        bind(OutboxRelayProducer.class).in(Singleton.class);
        bind(TradeCapturedConsumer.class).in(Singleton.class);
        bind(VolumeSupersededConsumer.class).in(Singleton.class);
        bind(SettlementPublishedConsumer.class).in(Singleton.class);
        bind(CurveTickConsumer.class).in(Singleton.class);
    }
}
