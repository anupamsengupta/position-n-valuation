package com.power.posval.domain.service;

import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.service.MarketDataService;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Default implementation of {@link MarketDataService}.
 * Delegates to repository ports.
 */
public class DefaultMarketDataService implements MarketDataService {

    private final MarketDataRepository repo;

    public DefaultMarketDataService(MarketDataRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<MarketDataLookup> findFixing(String tenantId, String series, Instant intervalStart) {
        return repo.findFixing(tenantId, series, intervalStart);
    }

    @Override
    public Optional<MarketDataLookup> findForwardCurve(String tenantId, String series,
                                                         YearMonth pillar, Instant asOfDate) {
        return repo.findForwardCurve(tenantId, series, pillar, asOfDate);
    }

    @Override
    public Optional<MarketDataLookup> findIndex(String tenantId, String series, String refMonth) {
        return repo.findIndex(tenantId, series, refMonth);
    }

    @Override
    public Optional<MarketDataLookup> findFxRate(String tenantId, String pair, Instant referenceDate) {
        return repo.findFxRate(tenantId, pair, referenceDate);
    }

    @Override
    public void saveFixing(String tenantId, String series, Instant intervalStart, MarketDataLookup lookup) {
        repo.saveFixing(tenantId, series, intervalStart, lookup);
    }

    @Override
    public void saveForwardCurve(String tenantId, String series, YearMonth pillar,
                                  Instant asOfDate, MarketDataLookup lookup) {
        repo.saveForwardCurve(tenantId, series, pillar, asOfDate, lookup);
    }

    @Override
    public void saveIndex(String tenantId, String series, String refMonth, MarketDataLookup lookup) {
        repo.saveIndex(tenantId, series, refMonth, lookup);
    }

    @Override
    public void saveFxRate(String tenantId, String currencyPair,
                            Instant referenceDate, MarketDataLookup lookup) {
        repo.saveFxRate(tenantId, currencyPair, referenceDate, lookup);
    }
}
