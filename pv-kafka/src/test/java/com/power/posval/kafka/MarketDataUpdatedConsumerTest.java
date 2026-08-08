package com.power.posval.kafka;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import com.power.posval.domain.port.repository.DependencyEdge;
import com.power.posval.domain.port.repository.DependencyIndex;
import com.power.posval.domain.service.PrunePolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataUpdatedConsumerTest {

    private MarketDataUpdatedConsumer consumer(MarketDataCache cache) {
        return consumer(cache, new StubDependencyIndex(List.of()), new ArrayList<>());
    }

    private MarketDataUpdatedConsumer consumer(MarketDataCache cache,
                                                DependencyIndex depIndex,
                                                List<Object> publishedEvents) {
        DomainEventPublisher pub = publishedEvents::add;
        return new MarketDataUpdatedConsumer(cache, depIndex, pub);
    }

    @Test
    void fullSeriesInvalidationDelegatesToCache() {
        var calls = new ArrayList<String>();
        MarketDataCache cache = new StubMarketDataCache() {
            @Override
            public void invalidate(String tenantId, MarketDataType type, String series) {
                calls.add(tenantId + ":" + type + ":" + series);
            }
        };

        var c = consumer(cache);
        c.handle(new MarketDataUpdated(
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

        var c = consumer(cache);
        c.handle(new MarketDataUpdated(
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

        var c = consumer(cache);
        var event = new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "S", null, null, 1L, Instant.now());

        c.handle(event);
        c.handle(event); // idempotent — both should process

        assertEquals(2, callCount[0]);
    }

    @Test
    void rangeUpdate_usesS8DependencyIndex_publishesRevalRequests() {
        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");
        UUID posId1 = UUID.randomUUID();
        UUID posId2 = UUID.randomUUID();

        var publishedEvents = new ArrayList<Object>();
        var depIndex = new StubDependencyIndex(List.of(posId1, posId2));

        var c = consumer(new StubMarketDataCache(), depIndex, publishedEvents);

        c.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "EPEX_DA15",
            start, end, 5L, Instant.now()));

        assertEquals(2, publishedEvents.size());
        var reval1 = (SettlementRevaluationRequested) publishedEvents.get(0);
        var reval2 = (SettlementRevaluationRequested) publishedEvents.get(1);
        assertEquals(posId1, reval1.positionId());
        assertEquals(posId2, reval2.positionId());
        assertEquals("MARKET_DATA", reval1.triggerType());
        assertEquals(start, reval1.intervalStart());
        assertEquals(end, reval1.intervalEnd());
    }

    @Test
    void rangeUpdate_noAffectedPositions_publishesNothing() {
        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");

        var publishedEvents = new ArrayList<Object>();
        var depIndex = new StubDependencyIndex(List.of()); // no affected positions

        var c = consumer(new StubMarketDataCache(), depIndex, publishedEvents);

        c.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "NORDPOOL_SYS",
            start, end, 5L, Instant.now()));

        assertEquals(0, publishedEvents.size());
    }

    @Test
    void fullSeriesUpdate_doesNotPublishRevaluationRequests() {
        var publishedEvents = new ArrayList<Object>();
        // Even with affected positions in the index, full-series (null range) should not trigger
        var depIndex = new StubDependencyIndex(List.of(UUID.randomUUID()));

        var c = consumer(new StubMarketDataCache(), depIndex, publishedEvents);

        c.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "EPEX_DA15",
            null, null, 5L, Instant.now()));

        assertEquals(0, publishedEvents.size());
    }

    @Test
    void dependencyIndex_receivesCorrectSeriesKey() {
        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");

        var queriedSeries = new ArrayList<String>();
        var depIndex = new StubDependencyIndex(List.of()) {
            @Override
            public List<UUID> findAffectedPositionIds(String tenantId, String inputSeriesKey,
                                                       Instant rangeStart, Instant rangeEnd) {
                queriedSeries.add(inputSeriesKey);
                return super.findAffectedPositionIds(tenantId, inputSeriesKey, rangeStart, rangeEnd);
            }
        };

        var c = consumer(new StubMarketDataCache(), depIndex, new ArrayList<>());
        c.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "EPEX_DA15",
            start, end, 5L, Instant.now()));

        assertEquals(1, queriedSeries.size());
        assertEquals("EPEX_DA15", queriedSeries.get(0));
    }

    // ── Stubs ──────────────────────────────────────────────────────────

    private static class StubMarketDataCache implements MarketDataCache {
        @Override public Optional<MarketDataLookup> get(String t, MarketDataType type, String s, String k) { return Optional.empty(); }
        @Override public void put(String t, MarketDataType type, String s, String k, MarketDataLookup v) {}
        @Override public Optional<VolSurfaceLookup> getVolSurface(String t, String s, String k) { return Optional.empty(); }
        @Override public void putVolSurface(String t, String s, String k, VolSurfaceLookup v) {}
        @Override public void invalidate(String t, MarketDataType type, String s) {}
        @Override public void invalidate(String t, MarketDataType type, String s, Instant rs, Instant re) {}
    }

    private static class StubDependencyIndex implements DependencyIndex {
        private final List<UUID> positionIds;
        StubDependencyIndex(List<UUID> positionIds) { this.positionIds = positionIds; }
        @Override public void upsert(DependencyEdge edge) {}
        @Override public List<DependencyEdge> findAffectedCells(String t, String s, DeliveryRange r, String f) { return List.of(); }
        @Override public void prune(String t, PrunePolicy p) {}
        @Override public List<UUID> findAffectedPositionIds(String t, String s, Instant rs, Instant re) {
            return new ArrayList<>(positionIds);
        }
    }
}
