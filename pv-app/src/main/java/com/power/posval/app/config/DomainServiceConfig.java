package com.power.posval.app.config;

import com.google.inject.Injector;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.service.*;
import com.power.posval.domain.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Bridges Guice-created domain services into the Spring ApplicationContext.
 * D-13: Spring @Bean methods delegate to {@code injector.getInstance()} and
 * do NOT construct domain classes with {@code new}.
 *
 * <p>The Guice {@link Injector} is created by {@link GuiceConfig} and wired
 * via {@link ConfigModule} + {@link com.power.posval.guice.DomainModule}.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public NumericPrecision numericPrecision(Injector injector) {
        return injector.getInstance(NumericPrecision.class);
    }

    @Bean
    public TradeCaptureHandler tradeCaptureHandler(Injector injector) {
        return injector.getInstance(TradeCaptureHandler.class);
    }

    @Bean
    public TradeAmendHandler tradeAmendHandler(Injector injector) {
        return injector.getInstance(TradeAmendHandler.class);
    }

    @Bean
    public TradeCancelHandler tradeCancelHandler(Injector injector) {
        return injector.getInstance(TradeCancelHandler.class);
    }

    @Bean
    public PriceEvaluator priceEvaluator(Injector injector) {
        return injector.getInstance(PriceEvaluator.class);
    }

    @Bean
    public PriceExpressionRepository priceExpressionRepository(Injector injector) {
        return injector.getInstance(PriceExpressionRepository.class);
    }

    @Bean
    @Primary
    public VolumeSeriesRepository volumeSeriesRepository(Injector injector) {
        return injector.getInstance(VolumeSeriesRepository.class);
    }

    @Bean
    public VolumeResolver volumeResolver(Injector injector) {
        return injector.getInstance(VolumeResolver.class);
    }

    @Bean
    public SettlementRevaluationService settlementRevaluationService(Injector injector) {
        return injector.getInstance(SettlementRevaluationService.class);
    }

    @Bean
    public RollupMaterializationService rollupMaterializationService(Injector injector) {
        return injector.getInstance(RollupMaterializationService.class);
    }

    @Bean
    public PositionQueryService positionQueryService(Injector injector) {
        return injector.getInstance(PositionQueryService.class);
    }

    @Bean
    public SettlementQueryService settlementQueryService(Injector injector) {
        return injector.getInstance(SettlementQueryService.class);
    }

    @Bean
    public MarketDataService marketDataService(Injector injector) {
        return injector.getInstance(MarketDataService.class);
    }

    @Bean
    public VolumeSeriesQueryService volumeSeriesQueryService(Injector injector) {
        return injector.getInstance(VolumeSeriesQueryService.class);
    }

    @Bean
    public RollupQueryService rollupQueryService(Injector injector) {
        return injector.getInstance(RollupQueryService.class);
    }

    @Bean
    public SettlementMaterializationJob settlementMaterializationJob(Injector injector) {
        return injector.getInstance(SettlementMaterializationJob.class);
    }
}
