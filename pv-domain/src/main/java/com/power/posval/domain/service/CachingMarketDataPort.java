package com.power.posval.domain.service;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.*;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.tenant.TenantContext;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

/**
 * Production {@link MarketDataPort} implementation.
 * Cache hit -> return, miss -> DB load -> cache populate.
 * Lives in pv-domain because it depends only on domain ports.
 * Pattern #29, S4.
 */
public class CachingMarketDataPort implements MarketDataPort {

    private final MarketDataCache cache;
    private final MarketDataRepository repository;
    private final TenantContext tenantContext;

    @Inject
    public CachingMarketDataPort(MarketDataCache cache,
                                  MarketDataRepository repository,
                                  TenantContext tenantContext) {
        this.cache = cache;
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    @Override
    public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
        String tenant = tenantContext.currentTenantId();
        String lookupKey = intervalStart.toString();
        return cache.get(tenant, MarketDataType.FIXING, series, lookupKey)
            .orElseGet(() -> {
                var result = repository.findFixing(tenant, series, intervalStart)
                    .orElse(provisional(series, intervalStart));
                cache.put(tenant, MarketDataType.FIXING, series, lookupKey, result);
                return result;
            });
    }

    @Override
    public MarketDataLookup lookupIndex(String series, String refMonthExpression,
                                         DeliveryPeriod deliveryPeriod) {
        String tenant = tenantContext.currentTenantId();
        String lookupKey = refMonthExpression;
        return cache.get(tenant, MarketDataType.INDEX, series, lookupKey)
            .orElseGet(() -> {
                var result = repository.findIndex(tenant, series, refMonthExpression)
                    .orElse(provisional(series, deliveryPeriod.start().toInstant()));
                cache.put(tenant, MarketDataType.INDEX, series, lookupKey, result);
                return result;
            });
    }

    @Override
    public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar,
                                                Instant asOfDate) {
        String tenant = tenantContext.currentTenantId();
        String lookupKey = pillar.toString() + ":" + asOfDate.toString();
        return cache.get(tenant, MarketDataType.FORWARD_CURVE, series, lookupKey)
            .orElseGet(() -> {
                var result = repository.findForwardCurve(tenant, series, pillar, asOfDate)
                    .orElse(provisional(series, asOfDate));
                cache.put(tenant, MarketDataType.FORWARD_CURVE, series, lookupKey, result);
                return result;
            });
    }

    @Override
    public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
        String tenant = tenantContext.currentTenantId();
        String lookupKey = referenceDate.toString();
        return cache.get(tenant, MarketDataType.FX_RATE, currencyPair, lookupKey)
            .orElseGet(() -> {
                var result = repository.findFxRate(tenant, currencyPair, referenceDate)
                    .orElse(new MarketDataLookup(BigDecimal.ONE, 0L, currencyPair,
                        referenceDate, QualityState.PROVISIONAL));
                cache.put(tenant, MarketDataType.FX_RATE, currencyPair, lookupKey, result);
                return result;
            });
    }

    @Override
    public MarketDataLookup lookupAtVersion(String series, Instant intervalStart,
                                             long versionId) {
        // Always bypass cache for reproducibility (FR-048f)
        String tenant = tenantContext.currentTenantId();
        return repository.findAtVersion(tenant, series, intervalStart, versionId)
            .orElse(provisional(series, intervalStart));
    }

    @Override
    public VolSurfaceLookup lookupVolSurface(String surfaceId, double strikeDelta,
                                              String expiryTenor, Instant asOfDate) {
        String tenant = tenantContext.currentTenantId();
        String lookupKey = strikeDelta + ":" + expiryTenor + ":" + asOfDate.toString();
        return cache.getVolSurface(tenant, surfaceId, lookupKey)
            .orElseGet(() -> {
                var result = repository.findVolSurface(tenant, surfaceId,
                        strikeDelta, expiryTenor, asOfDate)
                    .orElse(new VolSurfaceLookup(BigDecimal.ZERO, 0L, surfaceId,
                        strikeDelta, expiryTenor, asOfDate, QualityState.PROVISIONAL));
                cache.putVolSurface(tenant, surfaceId, lookupKey, result);
                return result;
            });
    }

    @Override
    public MarketDataLookup lookupSpread(String series, Instant intervalStart) {
        String tenant = tenantContext.currentTenantId();
        String lookupKey = intervalStart.toString();
        return cache.get(tenant, MarketDataType.SPREAD, series, lookupKey)
            .orElseGet(() -> {
                var result = repository.findSpread(tenant, series, intervalStart)
                    .orElse(provisional(series, intervalStart));
                cache.put(tenant, MarketDataType.SPREAD, series, lookupKey, result);
                return result;
            });
    }

    private static MarketDataLookup provisional(String series, Instant referenceTime) {
        return new MarketDataLookup(BigDecimal.ZERO, 0L, series, referenceTime,
            QualityState.PROVISIONAL);
    }
}
