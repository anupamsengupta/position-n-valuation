package com.power.posval.app.config;

import com.power.posval.app.cache.InMemoryMarketDataCache;
import com.power.posval.app.cache.InMemoryVolumeCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    public InMemoryMarketDataCache marketDataCache() {
        return new InMemoryMarketDataCache();
    }

    @Bean
    public InMemoryVolumeCache volumeCache() {
        return new InMemoryVolumeCache();
    }
}
