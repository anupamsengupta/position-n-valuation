package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.service.stub.JsonMarketDataPort;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import com.power.posval.domain.port.DefaultNumericPrecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SettlementMaterializationJobTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    /** Simple cell repo that captures saved cells for test assertions. */
    private static SettlementCellRepository capturingCellRepo(List<SettlementCell> target) {
        return new SettlementCellRepository() {
            @Override
            public void save(SettlementCell cell) {
                target.add(cell);
            }

            @Override
            public List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                                        Instant rangeStart, Instant rangeEnd) {
                return target.stream()
                    .filter(c -> c.tenantId().equals(tenantId) && c.positionId().equals(positionId))
                    .toList();
            }
        };
    }

    /**
     * Creates a VolumeSeriesRepository that returns a single VolumeSeries with fixed intervals.
     */
    private static VolumeSeriesRepository stubSeriesRepo(SeriesKey key, DeliveryRange range,
                                                          List<VolumeInterval> intervals) {
        ZonedDateTime start = range.startInstant();
        ZonedDateTime end = range.endInstant();
        VolumeSeries series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(key)
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(new DeliveryPeriod(start, end, range.deliveryTimezone()))
            .qualityState(QualityState.EFFECTIVE)
            .transactionTime(Instant.now())
            .intervals(intervals)
            .build();

        return new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) {}
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String sk) {
                return sk.equals(key.value()) ? Optional.of(series) : Optional.empty();
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String tid, int tv) { return false; }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };
    }

    @Test
    void endToEndWithStubsProducesNonZeroCells() {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new DefaultPriceEvaluator(new DefaultNumericPrecision());

        var savedCells = new ArrayList<SettlementCell>();
        var publishedEvents = new ArrayList<>();
        DomainEventPublisher eventPublisher = publishedEvents::add;

        // Use fixed-price expression (ID=1) -> 85.00 EUR/MWh
        UUID fixedPriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        SeriesKey seriesKey = new SeriesKey("VS-TEST-001");
        DeliveryRange range = DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET);

        // Create test volume interval
        List<VolumeInterval> intervals = List.of(
            new DefaultVolumeInterval(
                UUID.randomUUID(),
                Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-01T00:15:00Z"),
                new BigDecimal("50.0"),   // MW
                new BigDecimal("12.5"),   // MWh
                1, null));

        var seriesRepo = stubSeriesRepo(seriesKey, range, intervals);
        var resolver = new ProfileResolver(seriesRepo);

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher);

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-9999")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(fixedPriceExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);
        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()), "price should be 85.00");
        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0, "amount should be positive");
        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0,
            "Settlement cell amount must be non-zero");
        assertTrue(cell.activeLeaves().contains("FIXED_85"));
        assertEquals(1, publishedEvents.size());
    }

    @Test
    void indexPlusSpreadExpressionProducesCorrectPrice() {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new DefaultPriceEvaluator(new DefaultNumericPrecision());

        var savedCells = new ArrayList<SettlementCell>();
        DomainEventPublisher eventPublisher = e -> {};

        UUID indexSpreadExprId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        SeriesKey seriesKey = new SeriesKey("VS-TEST-002");
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
        var resolver = new ProfileResolver(seriesRepo);

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher);

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-8888")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(indexSpreadExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);
        // EPEX_DA15_SETTLE at 2025-03-01T00:00:00Z = 72.55, + 5.00 = 77.55
        assertEquals(0, new BigDecimal("77.55").compareTo(cell.price()), "price should be 77.55");
        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(cell.activeLeaves().contains("EPEX_DA15"));
        assertTrue(cell.activeLeaves().contains("SPREAD_5"));
    }
}
