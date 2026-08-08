package com.power.posval.domain.port.service;

import com.power.posval.domain.port.marketdata.MarketDataLookup;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Service interface for market data read/write operations.
 * Decouples controllers from repository ports.
 */
public interface MarketDataService {

    Optional<MarketDataLookup> findFixing(String tenantId, String series, Instant intervalStart);

    Optional<MarketDataLookup> findForwardCurve(String tenantId, String series,
                                                  YearMonth pillar, Instant asOfDate);

    Optional<MarketDataLookup> findIndex(String tenantId, String series, String refMonth);

    Optional<MarketDataLookup> findFxRate(String tenantId, String pair, Instant referenceDate);

    void saveFixing(String tenantId, String series, Instant intervalStart, MarketDataLookup lookup);

    void saveForwardCurve(String tenantId, String series, YearMonth pillar,
                           Instant asOfDate, MarketDataLookup lookup);

    void saveIndex(String tenantId, String series, String refMonth, MarketDataLookup lookup);

    void saveFxRate(String tenantId, String currencyPair,
                     Instant referenceDate, MarketDataLookup lookup);
}
