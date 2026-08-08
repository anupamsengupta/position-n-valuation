package com.power.posval.kafka;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataUpdatedConsumerTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private MarketDataUpdatedConsumer consumer(MarketDataCache cache) {
        return consumer(cache, new StubLedgerRepo(List.of()), new ArrayList<>());
    }

    private MarketDataUpdatedConsumer consumer(MarketDataCache cache,
                                                PositionLedgerRepository ledgerRepo,
                                                List<Object> publishedEvents) {
        DomainEventPublisher pub = publishedEvents::add;
        return new MarketDataUpdatedConsumer(cache, ledgerRepo, pub);
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
    void rangeUpdate_publishesRevaluationRequestsForAffectedPositions() {
        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("t1")
            .tradeId("T-1")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .status("ACTIVE")
            .build();

        var publishedEvents = new ArrayList<Object>();
        var c = consumer(new StubMarketDataCache(),
            new StubLedgerRepo(List.of(position)), publishedEvents);

        c.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "EPEX_DA15",
            start, end, 5L, Instant.now()));

        assertEquals(1, publishedEvents.size());
        assertInstanceOf(SettlementRevaluationRequested.class, publishedEvents.get(0));
        var reval = (SettlementRevaluationRequested) publishedEvents.get(0);
        assertEquals(position.id(), reval.positionId());
        assertEquals("MARKET_DATA", reval.triggerType());
        assertEquals(start, reval.intervalStart());
        assertEquals(end, reval.intervalEnd());
    }

    @Test
    void fullSeriesUpdate_doesNotPublishRevaluationRequests() {
        var publishedEvents = new ArrayList<Object>();
        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("t1")
            .tradeId("T-1")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .status("ACTIVE")
            .build();

        var c = consumer(new StubMarketDataCache(),
            new StubLedgerRepo(List.of(position)), publishedEvents);

        // null range → full series invalidation, no reval requests
        c.handle(new MarketDataUpdated(
            "t1", MarketDataType.FIXING, "EPEX_DA15",
            null, null, 5L, Instant.now()));

        assertEquals(0, publishedEvents.size());
    }

    private static class StubMarketDataCache implements MarketDataCache {
        @Override public Optional<MarketDataLookup> get(String t, MarketDataType type, String s, String k) { return Optional.empty(); }
        @Override public void put(String t, MarketDataType type, String s, String k, MarketDataLookup v) {}
        @Override public Optional<VolSurfaceLookup> getVolSurface(String t, String s, String k) { return Optional.empty(); }
        @Override public void putVolSurface(String t, String s, String k, VolSurfaceLookup v) {}
        @Override public void invalidate(String t, MarketDataType type, String s) {}
        @Override public void invalidate(String t, MarketDataType type, String s, Instant rs, Instant re) {}
    }

    private static class StubLedgerRepo implements PositionLedgerRepository {
        private final List<PositionLedgerEntry> store;
        StubLedgerRepo(List<PositionLedgerEntry> store) { this.store = store; }
        @Override public void save(PositionLedgerEntry e) {}
        @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
        @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return new ArrayList<>(store); }
        @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
        @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
    }
}
