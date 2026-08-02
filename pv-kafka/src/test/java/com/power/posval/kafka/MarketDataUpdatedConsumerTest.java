package com.power.posval.kafka;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataUpdatedConsumerTest {

    @Test
    void fullSeriesInvalidationDelegatesToCache() {
        var calls = new ArrayList<String>();
        MarketDataCache cache = new StubMarketDataCache() {
            @Override
            public void invalidate(String tenantId, MarketDataType type, String series) {
                calls.add(tenantId + ":" + type + ":" + series);
            }
        };

        var consumer = new MarketDataUpdatedConsumer(cache);
        consumer.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "EPEX_DA15",
            null, null, 5L, Instant.now()));

        assertEquals(1, calls.size());
        assertEquals("t1:FIXING:EPEX_DA15", calls.get(0));
    }

    @Test
    void rangeInvalidationDelegatesToCache() {
        var calls = new ArrayList<String>();
        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");

        MarketDataCache cache = new StubMarketDataCache() {
            @Override
            public void invalidate(String tenantId, MarketDataType type, String series,
                                    Instant rangeStart, Instant rangeEnd) {
                calls.add(tenantId + ":" + type + ":" + series + ":" + rangeStart + ":" + rangeEnd);
            }
        };

        var consumer = new MarketDataUpdatedConsumer(cache);
        consumer.handle(new MarketDataUpdated(
            "t1", MarketDataType.FORWARD_CURVE, "EEX_BASE",
            start, end, 10L, Instant.now()));

        assertEquals(1, calls.size());
        assertTrue(calls.get(0).contains("FORWARD_CURVE"));
        assertTrue(calls.get(0).contains("2025-03-01"));
    }

    @Test
    void idempotentReprocessing() {
        var callCount = new int[]{0};
        MarketDataCache cache = new StubMarketDataCache() {
            @Override
            public void invalidate(String tenantId, MarketDataType type, String series) {
                callCount[0]++;
            }
        };

        var consumer = new MarketDataUpdatedConsumer(cache);
        var event = new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "S", null, null, 1L, Instant.now());

        consumer.handle(event);
        consumer.handle(event); // idempotent — both should process

        assertEquals(2, callCount[0]);
    }

    private static class StubMarketDataCache implements MarketDataCache {
        @Override public Optional<MarketDataLookup> get(String t, MarketDataType type, String s, String k) { return Optional.empty(); }
        @Override public void put(String t, MarketDataType type, String s, String k, MarketDataLookup v) {}
        @Override public Optional<VolSurfaceLookup> getVolSurface(String t, String s, String k) { return Optional.empty(); }
        @Override public void putVolSurface(String t, String s, String k, VolSurfaceLookup v) {}
        @Override public void invalidate(String t, MarketDataType type, String s) {}
        @Override public void invalidate(String t, MarketDataType type, String s, Instant rs, Instant re) {}
    }
}
