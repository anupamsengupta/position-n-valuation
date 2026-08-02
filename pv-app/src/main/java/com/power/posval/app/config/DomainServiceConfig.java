package com.power.posval.app.config;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.service.*;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.persistence.adapter.JpaVolumeSeriesRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class DomainServiceConfig {

    private static final String DEFAULT_TENANT = "default";

    @Bean
    public NumericPrecision numericPrecision() {
        return new DefaultNumericPrecision();
    }

    @Bean
    public TradeCaptureHandler tradeCaptureHandler(PositionLedgerRepository ledgerRepo,
                                                    DomainEventPublisher eventPublisher) {
        return new DefaultTradeCaptureHandler(ledgerRepo, eventPublisher);
    }

    @Bean
    public TradeAmendHandler tradeAmendHandler(PositionLedgerRepository ledgerRepo,
                                                DomainEventPublisher eventPublisher) {
        return new DefaultTradeAmendHandler(ledgerRepo, eventPublisher);
    }

    @Bean
    public TradeCancelHandler tradeCancelHandler(PositionLedgerRepository ledgerRepo,
                                                  DomainEventPublisher eventPublisher) {
        return new DefaultTradeCancelHandler(ledgerRepo, eventPublisher);
    }

    @Bean
    public PriceEvaluator priceEvaluator(NumericPrecision np) {
        return new DefaultPriceEvaluator(np);
    }

    @Bean
    public PriceExpressionRepository priceExpressionRepository() {
        return new JsonPriceExpressionRepository();
    }

    @Bean
    public VolumeSeriesRepository volumeSeriesRepository(JpaVolumeSeriesRepository jpaRepo) {
        return new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) { jpaRepo.save(s); }
            @Override public Optional<VolumeSeries> findById(UUID id) { return jpaRepo.findById(id); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String sk) {
                return jpaRepo.findCurrentBySeriesKey(DEFAULT_TENANT, sk);
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return jpaRepo.findByTenantId(DEFAULT_TENANT); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return jpaRepo.findAll(DEFAULT_TENANT, s); }
            @Override public boolean existsByTradeIdAndTradeVersion(String tid, int tv) {
                return jpaRepo.existsByTradeIdAndTradeVersion(tid, tv);
            }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) { jpaRepo.supersede(o, n); }
        };
    }

    @Bean
    public VolumeResolver volumeResolver(VolumeSeriesRepository volumeSeriesRepo,
                                          NumericPrecision np) {
        return new ProfileResolver(volumeSeriesRepo, np);
    }

    @Bean
    public SettlementMaterializationJob settlementMaterializationJob(
            VolumeResolver volumeResolver,
            PriceEvaluator priceEvaluator,
            MarketDataPort marketDataPort,
            PriceExpressionRepository priceExpressionRepo,
            SettlementCellRepository cellRepo,
            DomainEventPublisher eventPublisher,
            NumericPrecision np) {
        return new SettlementMaterializationJob(
                volumeResolver, priceEvaluator, marketDataPort,
                priceExpressionRepo, cellRepo, eventPublisher, np);
    }
}
