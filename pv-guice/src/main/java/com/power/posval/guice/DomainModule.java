package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.service.*;
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

        bind(PriceEvaluator.class)
            .to(PriceExpressionBasedEvaluator.class)
            .in(Singleton.class);

        bind(ProfileResolver.class).in(Singleton.class);
        bind(ForecastResolver.class).in(Singleton.class);
        bind(VolumeSeriesFactory.class).in(Singleton.class);

        bind(CacheInvalidationHandler.class).in(Singleton.class);
        bind(TradeIntervalCacheRebuilder.class).in(Singleton.class);
        bind(SettlementRevaluationService.class).in(Singleton.class);
        bind(RollupMaterializationService.class).in(Singleton.class);

        bind(PositionQueryService.class).to(DefaultPositionQueryService.class).in(Singleton.class);
        bind(SettlementQueryService.class).to(DefaultSettlementQueryService.class).in(Singleton.class);
        bind(MarketDataService.class).to(DefaultMarketDataService.class).in(Singleton.class);
        bind(VolumeSeriesQueryService.class).to(DefaultVolumeSeriesQueryService.class).in(Singleton.class);
        bind(RollupQueryService.class).to(DefaultRollupQueryService.class).in(Singleton.class);
    }
}
