package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.kafka.*;

/**
 * Guice module for Kafka infrastructure. §16.6.
 * Binds outbox relay and all consumers.
 */
public class KafkaModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(OutboxRelayProducer.class).in(Singleton.class);
        bind(TradeCapturedConsumer.class).in(Singleton.class);
        bind(VolumeSupersededConsumer.class).in(Singleton.class);
        bind(SettlementPublishedConsumer.class).in(Singleton.class);
        bind(CurveTickConsumer.class).in(Singleton.class);
    }
}
