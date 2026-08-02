package com.power.posval.domain.port.repository;

import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Port interface for market data persistence.
 * Read methods used by {@code CachingMarketDataPort} on cache miss.
 * Write methods used by ingestion pipelines.
 * Pattern #18, S4.
 */
public interface MarketDataRepository {

    // --- reads (return latest version by default) ---

    Optional<MarketDataLookup> findFixing(String tenantId, String series, Instant intervalStart);

    Optional<MarketDataLookup> findIndex(String tenantId, String series, String refMonthExpression);

    Optional<MarketDataLookup> findForwardCurve(String tenantId, String series,
                                                 YearMonth pillar, Instant asOfDate);

    Optional<MarketDataLookup> findFxRate(String tenantId, String currencyPair, Instant referenceDate);

    Optional<MarketDataLookup> findSpread(String tenantId, String series, Instant intervalStart);

    Optional<VolSurfaceLookup> findVolSurface(String tenantId, String surfaceId,
                                               double strikeDelta, String expiryTenor,
                                               Instant asOfDate);

    Optional<MarketDataLookup> findAtVersion(String tenantId, String series,
                                              Instant intervalStart, long versionId);

    // --- writes ---

    void saveFixing(String tenantId, String series, Instant intervalStart,
                    MarketDataLookup lookup);

    void saveForwardCurve(String tenantId, String series, YearMonth pillar,
                          Instant asOfDate, MarketDataLookup lookup);

    void saveFxRate(String tenantId, String currencyPair, Instant referenceDate,
                    MarketDataLookup lookup);

    void saveIndex(String tenantId, String series, String refMonthExpression,
                   MarketDataLookup lookup);

    void saveSpread(String tenantId, String series, Instant intervalStart,
                    MarketDataLookup lookup);

    void saveVolSurface(String tenantId, String surfaceId, double strikeDelta,
                        String expiryTenor, Instant asOfDate, VolSurfaceLookup lookup);
}
