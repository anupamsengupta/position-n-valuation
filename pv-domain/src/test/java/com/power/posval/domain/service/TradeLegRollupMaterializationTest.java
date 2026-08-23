package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.TradeLegRollupCell;
import com.power.posval.domain.port.repository.TradeLegRollupRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RollupMaterializationService#materializeTradeLegRollup}.
 *
 * <p>Reference deal: T-7788, tenant TN_0042, EPEX DE_LU wind PPA.
 * S12.1, Pattern #18.
 */
class TradeLegRollupMaterializationTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final String TENANT = "TN_0042";

    // -------------------------------------------------------------------------
    // Test case 1: single position, two cells — verifies FR-035 aggregation
    // -------------------------------------------------------------------------

    @Test
    void materializeTradeLegRollup_twoSettledCells_aggregatesCorrectly() {
        var savedRollups = new ArrayList<TradeLegRollupCell>();
        var deletedPositions = new ArrayList<UUID>();
        var pos = testPosition();

        var cells = List.of(
            testCell(pos.id(), "2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z",
                new BigDecimal("50.0"), new BigDecimal("12.5"),
                new BigDecimal("85.00"), new BigDecimal("1062.50"),
                new BigDecimal("28.00"), new BigDecimal("350.00"), new BigDecimal("-712.50")),
            testCell(pos.id(), "2025-03-01T00:15:00Z", "2025-03-01T00:30:00Z",
                new BigDecimal("48.0"), new BigDecimal("12.0"),
                new BigDecimal("85.00"), new BigDecimal("1020.00"),
                new BigDecimal("30.00"), new BigDecimal("360.00"), new BigDecimal("-660.00"))
        );

        var service = buildService(List.of(pos), cells, List.of(),
            savedRollups, deletedPositions);

        service.materializeTradeLegRollup(TENANT, pos.id(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T00:30:00Z"));

        // Should produce DAILY and MONTHLY cells — at least 1 MONTHLY
        assertFalse(savedRollups.isEmpty(), "Expected rollup cells to be saved");

        var monthly = savedRollups.stream()
            .filter(c -> c.granularity() == TimeGranularity.MONTHLY)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No MONTHLY rollup cell saved"));

        // FR-035: settledMwh = sum: 12.5 + 12.0 = 24.5
        assertEquals(0, new BigDecimal("24.5").compareTo(monthly.settledMwh()),
            "settledMwh should be sum: " + monthly.settledMwh());

        // FR-035: settledMw = TWA: (50*15 + 48*15) / (15+15) = 1470/30 = 49.0
        assertEquals(0, new BigDecimal("49").compareTo(monthly.settledMw()),
            "settledMw should be TWA: " + monthly.settledMw());

        // settledValue = sum: 1062.50 + 1020.00 = 2082.50
        assertEquals(0, new BigDecimal("2082.50").compareTo(monthly.settledValue()),
            "settledValue should be sum: " + monthly.settledValue());

        // marketValue = sum: 350.00 + 360.00 = 710.00
        assertEquals(0, new BigDecimal("710.00").compareTo(monthly.marketValue()),
            "marketValue should be sum: " + monthly.marketValue());

        // realizedPnl = sum: -712.50 + -660.00 = -1372.50
        assertEquals(0, new BigDecimal("-1372.50").compareTo(monthly.realizedPnl()),
            "realizedPnl should be sum: " + monthly.realizedPnl());

        // avgPrice = settledValue / settledMwh = 2082.50 / 24.5
        BigDecimal expectedAvgPrice = new BigDecimal("2082.50")
            .divide(new BigDecimal("24.5"), 8, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expectedAvgPrice.compareTo(monthly.avgPrice()),
            "avgPrice should be volume-weighted: " + monthly.avgPrice());

        // Delivery status: cells present, no forward
        assertEquals("SETTLED", monthly.deliveryStatus());
        assertFalse(monthly.hasForwardIntervals());

        // Position metadata propagated
        assertEquals(pos.id(), monthly.positionId());
        assertEquals(TENANT, monthly.tenantId());
        assertEquals("T-7788", monthly.tradeId());
        assertEquals("EUR", monthly.currency());
    }

    // -------------------------------------------------------------------------
    // Test case 2: position with no cells, forward intervals exist => FORWARD
    // -------------------------------------------------------------------------

    @Test
    void materializeTradeLegRollup_noCellsWithForward_deliveryStatusForward() {
        var savedRollups = new ArrayList<TradeLegRollupCell>();
        var deletedPositions = new ArrayList<UUID>();
        var pos = testPosition();

        // No settlement cells; S6b returns interval records
        var forwardIntervals = List.of(
            new TradeIntervalRecord("LEG-1",
                Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-01T00:15:00Z"),
                new BigDecimal("100.0"), new BigDecimal("25.0"),
                BigDecimal.ONE, "key-1", "hash-1")
        );

        var service = buildService(List.of(pos), List.of(), forwardIntervals,
            savedRollups, deletedPositions);

        service.materializeTradeLegRollup(TENANT, pos.id(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        assertFalse(savedRollups.isEmpty(), "Expected rollup cells for FORWARD position");

        var monthly = savedRollups.stream()
            .filter(c -> c.granularity() == TimeGranularity.MONTHLY)
            .findFirst()
            .orElseThrow();

        assertEquals("FORWARD", monthly.deliveryStatus());
        assertTrue(monthly.hasForwardIntervals());
        // Settled aggregates are zero
        assertEquals(0, BigDecimal.ZERO.compareTo(monthly.settledMw()));
        assertEquals(0, BigDecimal.ZERO.compareTo(monthly.settledMwh()));
        assertEquals(0, BigDecimal.ZERO.compareTo(monthly.settledValue()));
    }

    // -------------------------------------------------------------------------
    // Test case 3: position with cells AND forward intervals => PARTIAL
    // -------------------------------------------------------------------------

    @Test
    void materializeTradeLegRollup_cellsAndForward_deliveryStatusPartial() {
        var savedRollups = new ArrayList<TradeLegRollupCell>();
        var deletedPositions = new ArrayList<UUID>();
        var pos = testPosition();

        var cells = List.of(
            testCell(pos.id(), "2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z",
                new BigDecimal("50.0"), new BigDecimal("12.5"),
                new BigDecimal("85.00"), new BigDecimal("1062.50"),
                null, null, null)
        );
        var forwardIntervals = List.of(
            new TradeIntervalRecord("LEG-1",
                Instant.parse("2025-03-15T00:00:00Z"),
                Instant.parse("2025-03-15T00:15:00Z"),
                new BigDecimal("50.0"), new BigDecimal("12.5"),
                BigDecimal.ONE, "key-1", "hash-1")
        );

        var service = buildService(List.of(pos), cells, forwardIntervals,
            savedRollups, deletedPositions);

        service.materializeTradeLegRollup(TENANT, pos.id(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        var monthly = savedRollups.stream()
            .filter(c -> c.granularity() == TimeGranularity.MONTHLY)
            .findFirst()
            .orElseThrow();

        assertEquals("PARTIAL", monthly.deliveryStatus());
        assertTrue(monthly.hasForwardIntervals());
        // Some settled data present
        assertTrue(monthly.settledMwh().compareTo(BigDecimal.ZERO) > 0);
    }

    // -------------------------------------------------------------------------
    // Test case 4: position with cells only, no forward => SETTLED
    // -------------------------------------------------------------------------

    @Test
    void materializeTradeLegRollup_cellsOnlyNoForward_deliveryStatusSettled() {
        var savedRollups = new ArrayList<TradeLegRollupCell>();
        var deletedPositions = new ArrayList<UUID>();
        var pos = testPosition();

        var cells = List.of(
            testCell(pos.id(), "2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z",
                new BigDecimal("50.0"), new BigDecimal("12.5"),
                new BigDecimal("85.00"), new BigDecimal("1062.50"),
                null, null, null)
        );
        // No forward intervals
        var service = buildService(List.of(pos), cells, List.of(),
            savedRollups, deletedPositions);

        service.materializeTradeLegRollup(TENANT, pos.id(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        var monthly = savedRollups.stream()
            .filter(c -> c.granularity() == TimeGranularity.MONTHLY)
            .findFirst()
            .orElseThrow();

        assertEquals("SETTLED", monthly.deliveryStatus());
        assertFalse(monthly.hasForwardIntervals());
    }

    // -------------------------------------------------------------------------
    // Test case 5: superseded position => deleteByPositionId called, no save
    // -------------------------------------------------------------------------

    @Test
    void materializeTradeLegRollup_supersededPosition_deletesRollupAndNoSave() {
        var savedRollups = new ArrayList<TradeLegRollupCell>();
        var deletedPositions = new ArrayList<UUID>();

        // Position with knownTo set (superseded)
        var pos = PositionLedgerEntry.builder()
            .id(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"))
            .tenantId(TENANT)
            .tradeId("T-7788")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(BigDecimal.TEN)
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .portfolioId("PF-001")
            .deliveryPointId("DP-EPEX-DE")
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownTo(Instant.parse("2025-03-10T00:00:00Z"))   // superseded
            .status("SUPERSEDED")
            .build();

        var service = buildService(List.of(pos), List.of(), List.of(),
            savedRollups, deletedPositions);

        service.materializeTradeLegRollup(TENANT, pos.id(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-04-01T00:00:00Z"));

        // No cells should be saved — position is superseded
        assertTrue(savedRollups.isEmpty(), "No rollup cells should be saved for superseded position");

        // deleteByPositionId must have been called (S8.4, Option A)
        assertTrue(deletedPositions.contains(pos.id()),
            "deleteByPositionId should have been called for the superseded position");
    }

    // -------------------------------------------------------------------------
    // Test case 6: idempotency — two invocations produce identical save calls
    // -------------------------------------------------------------------------

    @Test
    void materializeTradeLegRollup_idempotent_identicalOutputOnSecondCall() {
        var savedRollups1 = new ArrayList<TradeLegRollupCell>();
        var savedRollups2 = new ArrayList<TradeLegRollupCell>();
        var pos = testPosition();
        var cells = List.of(
            testCell(pos.id(), "2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z",
                new BigDecimal("50.0"), new BigDecimal("12.5"),
                new BigDecimal("85.00"), new BigDecimal("1062.50"),
                null, null, null)
        );

        var service1 = buildService(List.of(pos), cells, List.of(), savedRollups1, new ArrayList<>());
        var service2 = buildService(List.of(pos), cells, List.of(), savedRollups2, new ArrayList<>());

        var rangeStart = Instant.parse("2025-03-01T00:00:00Z");
        var rangeEnd = Instant.parse("2025-04-01T00:00:00Z");

        service1.materializeTradeLegRollup(TENANT, pos.id(), rangeStart, rangeEnd);
        service2.materializeTradeLegRollup(TENANT, pos.id(), rangeStart, rangeEnd);

        assertEquals(savedRollups1.size(), savedRollups2.size(),
            "Both invocations must produce same number of rollup cells");

        // Find the MONTHLY cell from each invocation and compare key aggregates
        var monthly1 = savedRollups1.stream()
            .filter(c -> c.granularity() == TimeGranularity.MONTHLY).findFirst().orElseThrow();
        var monthly2 = savedRollups2.stream()
            .filter(c -> c.granularity() == TimeGranularity.MONTHLY).findFirst().orElseThrow();

        assertEquals(0, monthly1.settledMwh().compareTo(monthly2.settledMwh()));
        assertEquals(0, monthly1.settledValue().compareTo(monthly2.settledValue()));
        assertEquals(0, monthly1.settledMw().compareTo(monthly2.settledMw()));
        assertEquals(monthly1.deliveryStatus(), monthly2.deliveryStatus());
        assertEquals(monthly1.versionHash(), monthly2.versionHash());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private RollupMaterializationService buildService(
            List<PositionLedgerEntry> positions,
            List<SettlementCell> cells,
            List<TradeIntervalRecord> forwardIntervals,
            List<TradeLegRollupCell> savedRollups,
            List<UUID> deletedPositions) {

        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(SettlementCell c) {}
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
            @Override public void saveAll(String t, List<RollupCell> c) {}
        };

        TradeLegRollupRepository tradeLegRepo = new TradeLegRollupRepository() {
            @Override public List<TradeLegRollupCell> findByPortfolio(String t, String p, Instant s, Instant e, TimeGranularity g) { return List.of(); }
            @Override public void saveAll(String t, List<TradeLegRollupCell> c) { savedRollups.addAll(c); }
            @Override public void deleteByPositionId(String t, UUID id) { deletedPositions.add(id); }
        };

        TradeIntervalCache tradeIntervalCache = new TradeIntervalCache() {
            @Override public List<TradeIntervalRecord> getForTradeLeg(String t, String tl, Instant s, Instant e) {
                return forwardIntervals.stream()
                    .filter(r -> r.tradeLegId().equals(tl))
                    .toList();
            }
            @Override public void rebuild(String t, String tl, Instant s, Instant e) {}
            @Override public void writeAll(String t, List<TradeIntervalRecord> r) {}
        };

        return new RollupMaterializationService(
            cellRepo, ledgerRepo, rollupRepo, tradeLegRepo, tradeIntervalCache,
            new DefaultNumericPrecision());
    }

    private PositionLedgerEntry testPosition() {
        return PositionLedgerEntry.builder()
            .id(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"))
            .tenantId(TENANT)
            .tradeId("T-7788")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(new BigDecimal("100.0"))
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .portfolioId("PF-001")
            .deliveryPointId("DP-EPEX-DE")
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
            UUID.randomUUID(), TENANT, positionId,
            Instant.parse(startStr), Instant.parse(endStr),
            "SETTLEMENT", "PROVISIONAL",
            price, mw, mwh, amount,
            marketPrice, marketAmount, pnl,
            "EUR", Set.of(), Map.of(), Instant.now());
    }
}
