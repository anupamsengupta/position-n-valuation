package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCancel;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTradeCancelHandlerTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void cancel_createsClosedEntries() {
        var store = new ArrayList<PositionLedgerEntry>();
        var events = new ArrayList<Object>();

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
                return store.stream().filter(e -> e.tradeId().equals(tr)).toList();
            }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {
                nw.forEach(store::add);
            }
        };

        var handler = new DefaultTradeCancelHandler(repo, events::add);
        var cmd = new TradeCancel("T-7788", 1, "LEG-1", "TN_0042",
            "VOLUNTARY", Instant.now());

        List<PositionLedgerEntry> result = handler.handle(cmd);

        assertEquals(1, result.size());
        assertEquals("CANCELLED", result.get(0).status());
        assertEquals(1, events.size());
    }
}
