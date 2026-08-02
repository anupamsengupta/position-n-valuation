package com.power.posval.app.config;

import com.power.posval.app.cache.InMemoryMarketDataCache;
import com.power.posval.app.cache.InMemoryVolumeCache;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.cache.VolumeCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    public InMemoryMarketDataCache inMemoryMarketDataCache() {
        return new InMemoryMarketDataCache();
    }

    @Bean
    public MarketDataCache marketDataCache(InMemoryMarketDataCache cache) {
        return cache;
    }

    @Bean
    public InMemoryVolumeCache inMemoryVolumeCache() {
        return new InMemoryVolumeCache();
    }

    @Bean
    public VolumeCache volumeCache(InMemoryVolumeCache cache) {
        return cache;
    }
}
