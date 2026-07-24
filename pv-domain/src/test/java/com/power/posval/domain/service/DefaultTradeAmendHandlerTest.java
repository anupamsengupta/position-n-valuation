package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeAmend;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTradeAmendHandlerTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void amend_supersedesOldEntries_createsNewOnes() {
        var store = new ArrayList<PositionLedgerEntry>();
        var superseded = new ArrayList<List<PositionLedgerEntry>>();
        var events = new ArrayList<Object>();

        // Seed existing entry
        var existing = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-7788")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(new BigDecimal("10.0"))
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .status("ACTIVE")
            .build();
        store.add(existing);

        PositionLedgerRepository repo = new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) { store.add(e); }
            @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) {
                return store.stream().filter(e -> e.tradeId().equals(tr) && e.tradeLegId().equals(tl)).toList();
            }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {
                superseded.add(old);
                nw.forEach(store::add);
            }
        };

        DomainEventPublisher pub = events::add;
        var handler = new DefaultTradeAmendHandler(repo, pub);

        var cmd = new TradeAmend(
            "T-7788", 2, "LEG-1", "TN_0042",
            "BACKDATED_CORRECTION", Instant.now(),
            new BigDecimal("15.0"), UUID.randomUUID(),
            "PORTFOLIO-1", BigDecimal.ONE,
            new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET));

        List<PositionLedgerEntry> result = handler.handle(cmd);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).tradeVersion());
        assertEquals(1, superseded.size());
        assertEquals(1, events.size());
    }
}
