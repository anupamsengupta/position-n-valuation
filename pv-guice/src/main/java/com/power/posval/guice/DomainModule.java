package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
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
        bind(SettlementMaterializationJob.class).in(Singleton.class);
        bind(SettlementRevaluationService.class).in(Singleton.class);
        bind(RollupMaterializationService.class).in(Singleton.class);

        bind(PositionQueryService.class).to(DefaultPositionQueryService.class).in(Singleton.class);
        bind(SettlementQueryService.class).to(DefaultSettlementQueryService.class).in(Singleton.class);
        bind(MarketDataService.class).to(DefaultMarketDataService.class).in(Singleton.class);
        bind(VolumeSeriesQueryService.class).to(DefaultVolumeSeriesQueryService.class).in(Singleton.class);
        bind(RollupQueryService.class).to(DefaultRollupQueryService.class).in(Singleton.class);

        // ForwardMarkService — ADR-002: compute-on-demand.
        // Replaces StubForwardMarkService. Uses S6b volumes + S4 curves
        // via PriceEvaluator to compute forward MtM at query time.
        bind(ForwardMarkService.class)
            .to(DefaultForwardMarkService.class)
            .in(Singleton.class);

        // Dashboard query facade — Pattern #18, §9.1.
        // Binds DashboardQueryService → DefaultDashboardQueryService (Singleton).
        bind(DashboardQueryService.class)
            .to(DefaultDashboardQueryService.class)
            .in(Singleton.class);
    }

    /**
     * VolumeResolver → CachingVolumeResolver with ProfileResolver as delegate.
     * Cannot use bind().to() because CachingVolumeResolver's constructor takes
     * a VolumeResolver delegate (circular). ProfileResolver is the concrete delegate.
     */
    @Provides
    @Singleton
    VolumeResolver volumeResolver(ProfileResolver profileResolver,
                                   VolumeCache cache,
                                   VolumeSeriesRepository seriesRepo,
                                   NumericPrecision np) {
        return new CachingVolumeResolver(profileResolver, cache, seriesRepo, np);
    }
}
