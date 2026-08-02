package com.power.posval.domain.service;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.*;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachingMarketDataPortTest {

    private static final String TENANT = "tenant-A";
    private static final Instant NOW = Instant.parse("2025-03-01T00:00:00Z");
    private static final MarketDataLookup DB_FIXING = new MarketDataLookup(
        new BigDecimal("42.50"), 5L, "EPEX_DA15", NOW, QualityState.VALIDATED);

    private StubCache cache;
    private StubRepo repo;
    private CachingMarketDataPort port;

    @BeforeEach
    void setUp() {
        cache = new StubCache();
        repo = new StubRepo();
        TenantContext ctx = new TenantContext() {
            @Override public String currentTenantId() { return TENANT; }
            @Override public void setTenant(String t) {}
            @Override public void clear() {}
        };
        port = new CachingMarketDataPort(cache, repo, ctx);
    }

    @Test
    void cacheHitReturnsWithoutDbCall() {
        cache.fixingResult = Optional.of(DB_FIXING);
        MarketDataLookup result = port.lookupFixing("EPEX_DA15", NOW);
        assertEquals(DB_FIXING, result);
        assertEquals(0, repo.fixingCalls.get(), "DB should not be called on cache hit");
    }

    @Test
    void cacheMissLoadsFromDbAndPopulatesCache() {
        cache.fixingResult = Optional.empty();
        repo.fixingReturn = Optional.of(DB_FIXING);

        MarketDataLookup result = port.lookupFixing("EPEX_DA15", NOW);
        assertEquals(DB_FIXING, result);
        assertEquals(1, repo.fixingCalls.get());
        assertNotNull(cache.lastPutValue, "Cache should be populated on miss");
        assertEquals(DB_FIXING, cache.lastPutValue);
    }

    @Test
    void cacheMissWithNoDbDataReturnsProvisional() {
        cache.fixingResult = Optional.empty();
        repo.fixingReturn = Optional.empty();

        MarketDataLookup result = port.lookupFixing("EPEX_DA15", NOW);
        assertEquals(BigDecimal.ZERO, result.value());
        assertEquals(0L, result.versionId());
        assertEquals(QualityState.PROVISIONAL, result.qualityState());
    }

    @Test
    void lookupAtVersionBypassesCache() {
        cache.fixingResult = Optional.of(DB_FIXING);
        repo.atVersionReturn = Optional.of(new MarketDataLookup(
            new BigDecimal("41.00"), 3L, "EPEX_DA15", NOW, QualityState.VALIDATED));

        MarketDataLookup result = port.lookupAtVersion("EPEX_DA15", NOW, 3L);
        assertEquals(new BigDecimal("41.00"), result.value());
        assertEquals(3L, result.versionId());
    }

    @Test
    void fxMissDefaultsToOne() {
        cache.fixingResult = Optional.empty();
        repo.fxReturn = Optional.empty();

        MarketDataLookup result = port.lookupFxRate("EUR/USD", NOW);
        assertEquals(BigDecimal.ONE, result.value());
        assertEquals(QualityState.PROVISIONAL, result.qualityState());
    }

    @Test
    void tenantIsolation() {
        // Verify tenant is passed through to cache and repo
        cache.fixingResult = Optional.empty();
        repo.fixingReturn = Optional.of(DB_FIXING);

        port.lookupFixing("SERIES", NOW);
        assertEquals(TENANT, cache.lastTenantId);
        assertEquals(TENANT, repo.lastTenantId);
    }

    @Test
    void lookupVolSurfaceCacheMiss() {
        cache.volSurfaceResult = Optional.empty();
        var expected = new VolSurfaceLookup(new BigDecimal("0.25"), 7L, "DE_POWER",
            0.5, "3M", NOW, QualityState.VALIDATED);
        repo.volSurfaceReturn = Optional.of(expected);

        VolSurfaceLookup result = port.lookupVolSurface("DE_POWER", 0.5, "3M", NOW);
        assertEquals(expected, result);
    }

    @Test
    void lookupSpreadCacheMiss() {
        cache.fixingResult = Optional.empty();
        var expected = new MarketDataLookup(new BigDecimal("1.50"), 2L,
            "SPREAD_1", NOW, QualityState.VALIDATED);
        repo.spreadReturn = Optional.of(expected);

        MarketDataLookup result = port.lookupSpread("SPREAD_1", NOW);
        assertEquals(expected, result);
    }

    // --- stubs ---

    private static class StubCache implements MarketDataCache {
        Optional<MarketDataLookup> fixingResult = Optional.empty();
        Optional<VolSurfaceLookup> volSurfaceResult = Optional.empty();
        MarketDataLookup lastPutValue;
        String lastTenantId;

        @Override
        public Optional<MarketDataLookup> get(String tenantId, MarketDataType type,
                                                String series, String lookupKey) {
            lastTenantId = tenantId;
            return fixingResult;
        }

        @Override
        public void put(String tenantId, MarketDataType type,
                         String series, String lookupKey, MarketDataLookup value) {
            lastTenantId = tenantId;
            lastPutValue = value;
        }

        @Override
        public Optional<VolSurfaceLookup> getVolSurface(String tenantId, String surfaceId,
                                                          String lookupKey) {
            return volSurfaceResult;
        }

        @Override
        public void putVolSurface(String tenantId, String surfaceId,
                                   String lookupKey, VolSurfaceLookup value) {}

        @Override
        public void invalidate(String tenantId, MarketDataType type, String series) {}

        @Override
        public void invalidate(String tenantId, MarketDataType type, String series,
                                Instant rangeStart, Instant rangeEnd) {}
    }

    private static class StubRepo implements MarketDataRepository {
        final AtomicInteger fixingCalls = new AtomicInteger();
        Optional<MarketDataLookup> fixingReturn = Optional.empty();
        Optional<MarketDataLookup> atVersionReturn = Optional.empty();
        Optional<MarketDataLookup> fxReturn = Optional.empty();
        Optional<VolSurfaceLookup> volSurfaceReturn = Optional.empty();
        Optional<MarketDataLookup> spreadReturn = Optional.empty();
        String lastTenantId;

        @Override
        public Optional<MarketDataLookup> findFixing(String tenantId, String series,
                                                       Instant intervalStart) {
            lastTenantId = tenantId;
            fixingCalls.incrementAndGet();
            return fixingReturn;
        }

        @Override
        public Optional<MarketDataLookup> findIndex(String tenantId, String series,
                                                      String refMonthExpression) {
            return Optional.empty();
        }

        @Override
        public Optional<MarketDataLookup> findForwardCurve(String tenantId, String series,
                                                             YearMonth pillar, Instant asOfDate) {
            return Optional.empty();
        }

        @Override
        public Optional<MarketDataLookup> findFxRate(String tenantId, String currencyPair,
                                                        Instant referenceDate) {
            return fxReturn;
        }

        @Override
        public Optional<MarketDataLookup> findSpread(String tenantId, String series,
                                                        Instant intervalStart) {
            lastTenantId = tenantId;
            return spreadReturn;
        }

        @Override
        public Optional<VolSurfaceLookup> findVolSurface(String tenantId, String surfaceId,
                                                           double strikeDelta, String expiryTenor,
                                                           Instant asOfDate) {
            return volSurfaceReturn;
        }

        @Override
        public Optional<MarketDataLookup> findAtVersion(String tenantId, String series,
                                                           Instant intervalStart, long versionId) {
            return atVersionReturn;
        }

        @Override public void saveFixing(String t, String s, Instant i, MarketDataLookup l) {}
        @Override public void saveForwardCurve(String t, String s, YearMonth p, Instant a, MarketDataLookup l) {}
        @Override public void saveFxRate(String t, String c, Instant r, MarketDataLookup l) {}
        @Override public void saveIndex(String t, String s, String r, MarketDataLookup l) {}
        @Override public void saveSpread(String t, String s, Instant i, MarketDataLookup l) {}
        @Override public void saveVolSurface(String t, String s, double d, String e, Instant a, VolSurfaceLookup l) {}
    }
}
