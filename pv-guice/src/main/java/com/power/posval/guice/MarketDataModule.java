package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.service.CachingMarketDataPort;
import com.power.posval.kafka.MarketDataUpdatedConsumer;
import com.power.posval.persistence.adapter.JpaMarketDataRepository;
import com.power.posval.redis.RedisMarketDataCache;

/**
 * Guice module for production market data stack.
 * Install instead of {@link StubServiceModule}'s MarketDataPort binding.
 * §16.7, S4.
 */
public class MarketDataModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MarketDataRepository.class)
            .to(JpaMarketDataRepository.class)
            .in(Singleton.class);

        bind(MarketDataCache.class)
            .to(RedisMarketDataCache.class)
            .in(Singleton.class);

        bind(MarketDataPort.class)
            .to(CachingMarketDataPort.class)
            .in(Singleton.class);

        bind(MarketDataUpdatedConsumer.class)
            .in(Singleton.class);
    }
}
