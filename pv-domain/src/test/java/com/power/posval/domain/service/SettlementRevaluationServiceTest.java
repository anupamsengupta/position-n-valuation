package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.service.stub.JsonMarketDataPort;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SettlementRevaluationServiceTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void happyPath_deletesOldCells_createsNewCells() {
        var savedCells = new ArrayList<SettlementCell>();
        var deletedRanges = new ArrayList<String>();
        var publishedEvents = new ArrayList<>();

        var service = buildService(savedCells, deletedRanges, publishedEvents);

        var position = testPosition(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), // fixed price 85.00
            null);

        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-03-01T00:15:00Z");

        service.revalue(position, start, end);

        assertEquals(1, deletedRanges.size(), "Should delete old cells");
        assertEquals(1, savedCells.size(), "Should create 1 new cell");
        assertEquals(1, publishedEvents.size(), "Should publish 1 event");

        SettlementCell cell = savedCells.get(0);
        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()));
        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0);
        assertNull(cell.marketPrice(), "No market price expression → null");
        assertNull(cell.pnl(), "No market price → no PnL");
    }

    @Test
    void rangeClamping_onlyRevaluesOverlap() {
        var savedCells = new ArrayList<SettlementCell>();
        var deletedRanges = new ArrayList<String>();

        var service = buildService(savedCells, deletedRanges, new ArrayList<>());

        // Position delivery is March 2025 only
        var position = testPosition(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), null);

        // Revaluation range is Feb-Apr — only March should be revalued
        Instant start = Instant.parse("2025-02-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");

        service.revalue(position, start, end);

        assertEquals(1, deletedRanges.size());
        assertTrue(savedCells.size() >= 1, "Should have cells for March overlap");
    }

    @Test
    void noOverlap_isNoOp() {
        var savedCells = new ArrayList<SettlementCell>();
        var deletedRanges = new ArrayList<String>();

        var service = buildService(savedCells, deletedRanges, new ArrayList<>());

        var position = testPosition(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), null);

        // Completely outside position delivery range (April)
        Instant start = Instant.parse("2025-04-01T00:00:00Z");
        Instant end = Instant.parse("2025-05-01T00:00:00Z");

        service.revalue(position, start, end);

        assertEquals(0, deletedRanges.size(), "No delete when no overlap");
        assertEquals(0, savedCells.size(), "No cells when no overlap");
    }

    @Test
    void withMarketPriceExpression_producesPnl() {
        var savedCells = new ArrayList<SettlementCell>();

        var service = buildService(savedCells, new ArrayList<>(), new ArrayList<>());

        // Trade price: EXPR-1 (85.00), Market price: EXPR-2 (EPEX+spread ~28.06)
        var position = testPosition(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"));

        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-03-01T00:15:00Z");

        service.revalue(position, start, end);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);

        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()));
        assertNotNull(cell.marketPrice());
        assertTrue(cell.marketPrice().subtract(new BigDecimal("28.06")).abs()
            .compareTo(new BigDecimal("0.01")) < 0,
            "Market price ≈ 28.06, got " + cell.marketPrice());
        assertNotNull(cell.pnl());
        assertTrue(cell.pnl().compareTo(BigDecimal.ZERO) < 0,
            "PnL should be negative (market < trade)");
    }

    // --- helpers ---

    private SettlementRevaluationService buildService(List<SettlementCell> savedCells,
                                                       List<String> deletedRanges,
                                                       List<Object> publishedEvents) {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());
        DomainEventPublisher eventPublisher = publishedEvents::add;

        SeriesKey seriesKey = new SeriesKey("VS-REVAL-001");
        DeliveryRange range = DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET);
        List<VolumeInterval> intervals = List.of(
            new DefaultVolumeInterval(
                UUID.randomUUID(),
                Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-01T00:15:00Z"),
                new BigDecimal("50.0"),
                new BigDecimal("12.5"),
                1, null));

        var seriesRepo = stubSeriesRepo(seriesKey, range, intervals);
        var resolver = new ProfileResolver(seriesRepo, new DefaultNumericPrecision());

        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(SettlementCell cell) { savedCells.add(cell); }
            @Override public List<SettlementCell> findByPosition(String t, UUID p, Instant s, Instant e) {
                return List.of();
            }
            @Override public int deleteByPositionAndInterval(String t, UUID p, Instant s, Instant e) {
                deletedRanges.add(t + ":" + p + ":" + s + ":" + e);
                return 1;
            }
        };

        return new SettlementRevaluationService(
            resolver, priceEvaluator, marketData, exprRepo,
            cellRepo, eventPublisher, new DefaultNumericPrecision());
    }

    private PositionLedgerEntry testPosition(UUID priceExprId, UUID marketPriceExprId) {
        return PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-REVAL")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(priceExprId)
            .marketPriceExpressionId(marketPriceExprId)
            .volumeSeriesKey(new SeriesKey("VS-REVAL-001"))
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();
    }

    private static VolumeSeriesRepository stubSeriesRepo(SeriesKey key, DeliveryRange range,
                                                          List<VolumeInterval> intervals) {
        VolumeSeries series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(key)
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(new DeliveryPeriod(
                range.startInstant(), range.endInstant(), range.deliveryTimezone()))
            .qualityState(QualityState.EFFECTIVE)
            .transactionTime(Instant.now())
            .intervals(intervals)
            .build();

        return new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) {}
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String t, String sk) {
                return sk.equals(key.value()) ? Optional.of(series) : Optional.empty();
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String tid, int tv) { return false; }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };
    }
}
