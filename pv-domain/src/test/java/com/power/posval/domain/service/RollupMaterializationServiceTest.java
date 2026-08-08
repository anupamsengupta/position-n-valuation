package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.PositionMonthSummary;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollupMaterializationServiceTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void materialize_aggregatesSettlementCellsIntoMonthlyRollup() {
        var savedRollups = new ArrayList<RollupCell>();
        var position = testPosition();
        var cells = List.of(
            testCell(position.id(), "2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z",
                new BigDecimal("50.0"), new BigDecimal("12.5"),
                new BigDecimal("85.00"), new BigDecimal("1062.50"),
                new BigDecimal("28.00"), new BigDecimal("350.00"), new BigDecimal("-712.50")),
            testCell(position.id(), "2025-03-01T00:15:00Z", "2025-03-01T00:30:00Z",
                new BigDecimal("48.0"), new BigDecimal("12.0"),
                new BigDecimal("85.00"), new BigDecimal("1020.00"),
                new BigDecimal("30.00"), new BigDecimal("360.00"), new BigDecimal("-660.00"))
        );

        var service = buildService(List.of(position), cells, savedRollups);

        service.materialize("TN_0042",
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        assertEquals(1, savedRollups.size(), "Should produce 1 monthly rollup");
        RollupCell rollup = savedRollups.get(0);

        // netMwh = 12.5 + 12.0 = 24.5
        assertEquals(0, new BigDecimal("24.5").compareTo(rollup.netMwh()),
            "netMwh should be sum: " + rollup.netMwh());

        // settledValue = 1062.50 + 1020.00 = 2082.50
        assertEquals(0, new BigDecimal("2082.50").compareTo(rollup.settledValue()),
            "settledValue should be sum: " + rollup.settledValue());

        // marketValue = 350.00 + 360.00 = 710.00
        assertEquals(0, new BigDecimal("710.00").compareTo(rollup.marketValue()),
            "marketValue should be sum: " + rollup.marketValue());

        // pnl = -712.50 + -660.00 = -1372.50
        assertEquals(0, new BigDecimal("-1372.50").compareTo(rollup.pnl()),
            "pnl should be sum: " + rollup.pnl());

        // netMw = TWA: (50*15 + 48*15) / (15+15) = 1470/30 = 49.0
        assertEquals(0, new BigDecimal("49").compareTo(rollup.netMw()),
            "netMw should be TWA: " + rollup.netMw());

        assertEquals(TimeGranularity.MONTHLY, rollup.granularity());
        assertEquals("DP-001", rollup.deliveryPointId());
        assertEquals("PF-001", rollup.portfolioId());
    }

    @Test
    void materialize_noPositions_isNoOp() {
        var savedRollups = new ArrayList<RollupCell>();
        var service = buildService(List.of(), List.of(), savedRollups);

        service.materialize("TN_0042",
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        assertTrue(savedRollups.isEmpty());
    }

    @Test
    void materialize_noCells_isNoOp() {
        var savedRollups = new ArrayList<RollupCell>();
        var position = testPosition();
        var service = buildService(List.of(position), List.of(), savedRollups);

        service.materialize("TN_0042",
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        assertTrue(savedRollups.isEmpty());
    }

    @Test
    void materialize_cellsWithoutMarketPrice_zeroMarketValue() {
        var savedRollups = new ArrayList<RollupCell>();
        var position = testPosition();
        var cell = testCell(position.id(), "2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z",
            new BigDecimal("50.0"), new BigDecimal("12.5"),
            new BigDecimal("85.00"), new BigDecimal("1062.50"),
            null, null, null);

        var service = buildService(List.of(position), List.of(cell), savedRollups);

        service.materialize("TN_0042",
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        assertEquals(1, savedRollups.size());
        RollupCell rollup = savedRollups.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(rollup.marketValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(rollup.pnl()));
    }

    // --- helpers ---

    private RollupMaterializationService buildService(List<PositionLedgerEntry> positions,
                                                        List<SettlementCell> cells,
                                                        List<RollupCell> savedRollups) {
        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(SettlementCell cell) {}
            @Override public List<SettlementCell> findByPosition(String t, UUID p, Instant s, Instant e) {
                return cells.stream()
                    .filter(c -> c.positionId().equals(p))
                    .filter(c -> c.intervalStart().isBefore(e) && c.intervalEnd().isAfter(s))
                    .toList();
            }
        };

        PositionLedgerRepository ledgerRepo = new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) {}
            @Override public Optional<PositionLedgerEntry> findById(UUID id) {
                return positions.stream().filter(p -> p.id().equals(id)).findFirst();
            }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return new ArrayList<>(positions); }
            @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
        };

        RollupRepository rollupRepo = new RollupRepository() {
            @Override public List<RollupCell> findByRange(String t, String d, String p, Instant s, Instant e, TimeGranularity g) { return List.of(); }
            @Override public void refresh(String t, Instant s, Instant e, TimeGranularity g) {}
            @Override public void saveAll(String t, List<RollupCell> c) { savedRollups.addAll(c); }
        };

        return new RollupMaterializationService(cellRepo, ledgerRepo, rollupRepo, new DefaultNumericPrecision());
    }

    private PositionLedgerEntry testPosition() {
        return PositionLedgerEntry.builder()
            .id(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"))
            .tenantId("TN_0042")
            .tradeId("T-ROLLUP")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .portfolioId("PF-001")
            .deliveryPointId("DP-001")
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();
    }

    private SettlementCell testCell(UUID positionId,
                                     String startStr, String endStr,
                                     BigDecimal mw, BigDecimal mwh,
                                     BigDecimal price, BigDecimal amount,
                                     BigDecimal marketPrice, BigDecimal marketAmount,
                                     BigDecimal pnl) {
        return new SettlementCell(
            UUID.randomUUID(), "TN_0042", positionId,
            Instant.parse(startStr), Instant.parse(endStr),
            "SETTLEMENT", "PROVISIONAL",
            price, mw, mwh, amount,
            marketPrice, marketAmount, pnl,
            "EUR", Set.of(), Map.of(), Instant.now());
    }
}
