package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTradeCaptureHandlerTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private final List<PositionLedgerEntry> savedEntries = new ArrayList<>();
    private final List<Object> publishedEvents = new ArrayList<>();

    private DefaultTradeCaptureHandler handler;

    @BeforeEach
    void setUp() {
        savedEntries.clear();
        publishedEvents.clear();

        PositionLedgerRepository stubRepo = new StubPositionLedgerRepository(savedEntries);
        DomainEventPublisher stubPublisher = publishedEvents::add;

        handler = new DefaultTradeCaptureHandler(stubRepo, stubPublisher);
    }

    @Test
    void singleMonthTrade_createsOneEntry() {
        var cmd = tradeCapture(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET));

        List<PositionLedgerEntry> entries = handler.handle(cmd);

        assertEquals(1, entries.size());
        assertEquals(1, savedEntries.size());
        assertEquals("ACTIVE", entries.get(0).status());
        assertEquals(1, publishedEvents.size());
    }

    @Test
    void multiMonthTrade_createsOneEntryPerMonth() {
        var cmd = tradeCapture(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0, CET));

        List<PositionLedgerEntry> entries = handler.handle(cmd);

        assertEquals(3, entries.size()); // March, April, May
        assertEquals(3, savedEntries.size());
    }

    @Test
    void entryFieldsMatchCommand() {
        var cmd = tradeCapture(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET));

        List<PositionLedgerEntry> entries = handler.handle(cmd);
        var entry = entries.get(0);

        assertEquals("TN_0042", entry.tenantId());
        assertEquals("T-7788", entry.tradeId());
        assertEquals("LEG-1", entry.tradeLegId());
        assertEquals(0, new BigDecimal("10.0").compareTo(entry.quantity()));
    }

    private TradeCapture tradeCapture(ZonedDateTime start, ZonedDateTime end) {
        return new TradeCapture(
            "T-7788", 1, "LEG-1", "TN_0042",
            new DeliveryPeriod(start, end, CET),
            new BigDecimal("10.0"), VolumeUnit.MW_CAPACITY,
            UUID.randomUUID(), "PORTFOLIO-1", "DE_LU",
            "BILATERAL_TRADE", Instant.now(),
            null, BigDecimal.ONE,
            new SeriesKey("VS-T7788-1"), null);
    }

    /** Stub repository that records saved entries. */
    private static class StubPositionLedgerRepository implements PositionLedgerRepository {
        private final List<PositionLedgerEntry> store;

        StubPositionLedgerRepository(List<PositionLedgerEntry> store) {
            this.store = store;
        }

        @Override public void save(PositionLedgerEntry entry) { store.add(entry); }
        @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
        @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, Instant b, Instant k) { return List.of(); }
        @Override public List<PositionLedgerEntry> findByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
        @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {
            nw.forEach(store::add);
        }
    }
}
