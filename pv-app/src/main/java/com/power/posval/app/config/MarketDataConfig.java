package com.power.posval.app.config;

import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.tenant.TenantContext;
import com.power.posval.domain.service.CachingMarketDataPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketDataConfig {

    @Bean
    public MarketDataPort marketDataPort(MarketDataCache cache,
                                          MarketDataRepository repository,
                                          TenantContext tenantContext) {
        return new CachingMarketDataPort(cache, repository, tenantContext);
    }
}
