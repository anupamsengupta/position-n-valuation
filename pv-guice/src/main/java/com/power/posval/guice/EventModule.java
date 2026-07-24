package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.ForwardMarkStore;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.persistence.event.OutboxDomainEventPublisher;
import com.power.posval.redis.RedisForwardMarkStore;

/**
 * Guice module for domain event publishing. §16.4.
 * Binds DomainEventPublisher → outbox adapter,
 * ForwardMarkStore → Redis adapter.
 */
public class EventModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(DomainEventPublisher.class)
            .to(OutboxDomainEventPublisher.class)
            .in(Singleton.class);

        bind(ForwardMarkStore.class)
            .to(RedisForwardMarkStore.class)
            .in(Singleton.class);
    }
}
