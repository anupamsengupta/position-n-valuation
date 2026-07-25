package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.MeteredActualRepository;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.service.stub.JsonMarketDataPort;
import com.power.posval.domain.service.stub.JsonMeteredActualRepository;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;

/**
 * Guice module for JSON-resource-backed stub implementations of external services.
 * Installed by default; swap for real adapter modules when services are available.
 * §16.7.
 */
public class StubServiceModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MarketDataPort.class)
            .to(JsonMarketDataPort.class)
            .in(Singleton.class);

        bind(PriceExpressionRepository.class)
            .to(JsonPriceExpressionRepository.class)
            .in(Singleton.class);

        bind(MeteredActualRepository.class)
            .to(JsonMeteredActualRepository.class)
            .in(Singleton.class);
    }
}
