package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.service.*;

/**
 * Guice module for domain service bindings. §16.1.
 */
public class DomainModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(NumericPrecision.class)
            .to(DefaultNumericPrecision.class)
            .in(Singleton.class);

        bind(TradeCaptureHandler.class)
            .to(DefaultTradeCaptureHandler.class)
            .in(Singleton.class);

        bind(TradeAmendHandler.class)
            .to(DefaultTradeAmendHandler.class)
            .in(Singleton.class);

        bind(TradeCancelHandler.class)
            .to(DefaultTradeCancelHandler.class)
            .in(Singleton.class);

        bind(CacheInvalidationHandler.class).in(Singleton.class);
        bind(TradeIntervalCacheRebuilder.class).in(Singleton.class);
    }
}
