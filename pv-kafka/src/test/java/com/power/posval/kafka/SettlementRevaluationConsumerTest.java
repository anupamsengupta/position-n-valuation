package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.service.SettlementRevaluationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SettlementRevaluationConsumerTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    /** Subclass that captures revalue calls instead of doing real work. */
    private static class CapturingRevaluationService extends SettlementRevaluationService {
        final List<UUID> revaluedPositions = new ArrayList<>();

        CapturingRevaluationService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void revalue(PositionLedgerEntry position, Instant start, Instant end) {
            revaluedPositions.add(position.id());
        }
    }

    @Test
    void loadsPositionAndCallsService() {
        var position = testPosition();
        var service = new CapturingRevaluationService();

        var consumer = new SettlementRevaluationConsumer(stubLedgerRepo(position), service);
        consumer.handle(new SettlementRevaluationRequested(
            "TN_0042", position.id(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T00:15:00Z"),
            "MARKET_DATA", Instant.now()));

        assertEquals(1, service.revaluedPositions.size());
        assertEquals(position.id(), service.revaluedPositions.get(0));
    }

    @Test
    void missingPosition_isNoOp() {
        PositionLedgerRepository ledgerRepo = new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) {}
            @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
        };

        var service = new CapturingRevaluationService();
        var consumer = new SettlementRevaluationConsumer(ledgerRepo, service);
        consumer.handle(new SettlementRevaluationRequested(
            "TN_0042", UUID.randomUUID(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T00:15:00Z"),
            "MARKET_DATA", Instant.now()));

        assertEquals(0, service.revaluedPositions.size(), "Should not revalue when position not found");
    }

    private PositionLedgerEntry testPosition() {
        return PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
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
    }

    private PositionLedgerRepository stubLedgerRepo(PositionLedgerEntry position) {
        return new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) {}
            @Override public Optional<PositionLedgerEntry> findById(UUID id) {
                return id.equals(position.id()) ? Optional.of(position) : Optional.empty();
            }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
        };
    }
}
