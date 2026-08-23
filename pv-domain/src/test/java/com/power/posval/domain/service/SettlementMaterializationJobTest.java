package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.DependencyEdge;
import com.power.posval.domain.port.repository.DependencyIndex;
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
    private static final DependencyIndex NO_OP_INDEX = new DependencyIndex() {
        @Override public void upsert(DependencyEdge edge) {}
        @Override public java.util.List<DependencyEdge> findAffectedCells(
            String t, String k, java.time.Instant rs, java.time.Instant re, String f) { return java.util.List.of(); }
        @Override public void prune(String t, com.power.posval.domain.service.PrunePolicy p) {}
    };

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
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

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
        var resolver = new ProfileResolver(seriesRepo, new DefaultNumericPrecision());

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher, new DefaultNumericPrecision(), NO_OP_INDEX);

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-9999")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN)
            .direction(TradeDirection.BUY)
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
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

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
        var resolver = new ProfileResolver(seriesRepo, new DefaultNumericPrecision());

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher, new DefaultNumericPrecision(), NO_OP_INDEX);

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-8888")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN)
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(indexSpreadExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);
        // EPEX_DA15_SETTLE at 2025-03-01T00:00:00Z = 83.90, + 3.20 = 87.10
        assertEquals(0, new BigDecimal("87.10").compareTo(cell.price()), "price should be 87.10");
        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(cell.activeLeaves().contains("EPEX_DA15"));
        assertTrue(cell.activeLeaves().contains("PREMIUM_3_20"));
    }

    @Test
    void dualPriceExpressions_producesMarketPriceAndPnl() {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

        var savedCells = new ArrayList<SettlementCell>();
        DomainEventPublisher eventPublisher = e -> {};

        // Trade price: EXPR-1 (fixed 85.00), Market price: EXPR-2 (EPEX+spread ~28.06)
        UUID tradePriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID marketPriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        SeriesKey seriesKey = new SeriesKey("VS-TEST-DUAL");
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

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher, new DefaultNumericPrecision(), NO_OP_INDEX);

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-DUAL")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN)
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(tradePriceExprId)
            .marketPriceExpressionId(marketPriceExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);

        // Trade price = 85.00
        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()));
        // Market price ≈ 87.10 (EPEX_DA15_SETTLE 83.90 + spread 3.20)
        assertNotNull(cell.marketPrice());
        assertTrue(cell.marketPrice().subtract(new BigDecimal("87.10")).abs()
            .compareTo(new BigDecimal("0.01")) < 0,
            "Market price should be ~87.10, got " + cell.marketPrice());
        // Market amount should be positive
        assertNotNull(cell.marketAmount());
        assertTrue(cell.marketAmount().compareTo(BigDecimal.ZERO) > 0);
        // PnL = marketAmount - tradeAmount (positive since 87.10 > 85)
        assertNotNull(cell.pnl());
        assertTrue(cell.pnl().compareTo(BigDecimal.ZERO) > 0,
            "PnL should be positive, got " + cell.pnl());
        // Active leaves should contain leaves from both expressions
        assertTrue(cell.activeLeaves().contains("FIXED_85"));
        assertTrue(cell.activeLeaves().contains("EPEX_DA15"));
    }

    @Test
    void sellDirection_producesNegativeVolumeAndAmount() {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

        var savedCells = new ArrayList<SettlementCell>();
        DomainEventPublisher eventPublisher = e -> {};

        UUID fixedPriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        SeriesKey seriesKey = new SeriesKey("VS-TEST-SELL");
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

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher, new DefaultNumericPrecision(), NO_OP_INDEX);

        // SELL direction: quantity is negative (signed by handler)
        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-SELL-001")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN.negate()) // -10 MW (SELL)
            .direction(TradeDirection.SELL)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(fixedPriceExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);

        // Price remains unsigned (it's the expression result)
        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()));
        // Volume and energy should be negative for SELL
        assertTrue(cell.volumeMw().signum() < 0, "volumeMw should be negative for SELL, got " + cell.volumeMw());
        assertTrue(cell.volumeMwh().signum() < 0, "volumeMwh should be negative for SELL, got " + cell.volumeMwh());
        // Amount = price * signedEnergy → negative
        assertTrue(cell.amount().signum() < 0, "amount should be negative for SELL, got " + cell.amount());
    }

    @Test
    void sellDirection_withMarketPrice_producesCorrectPnl() {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

        var savedCells = new ArrayList<SettlementCell>();
        DomainEventPublisher eventPublisher = e -> {};

        // Trade price: EXPR-1 (fixed 85.00), Market price: EXPR-2 (EPEX+spread ~87.10)
        UUID tradePriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID marketPriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        SeriesKey seriesKey = new SeriesKey("VS-TEST-SELL-PNL");
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

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher, new DefaultNumericPrecision(), NO_OP_INDEX);

        // SELL: market price (87.10) > trade price (85.00) → loss for seller
        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-SELL-PNL")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN.negate()) // SELL
            .direction(TradeDirection.SELL)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(tradePriceExprId)
            .marketPriceExpressionId(marketPriceExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);

        // For SELL: market > trade → PnL negative (loss for the seller)
        // pnl = marketAmount - tradeAmount = (87.10 * -12.5) - (85.00 * -12.5)
        //     = -1088.75 - (-1062.50) = -26.25
        assertNotNull(cell.pnl());
        assertTrue(cell.pnl().signum() < 0,
            "PnL should be negative when market > trade for SELL, got " + cell.pnl());
        // Amounts should be negative
        assertTrue(cell.amount().signum() < 0, "tradeAmount negative for SELL");
        assertTrue(cell.marketAmount().signum() < 0, "marketAmount negative for SELL");
    }

    /**
     * EXPR-4: Three-level CPI-escalated collar PPA with negative-price protection.
     * Formula: if(EPEX < 0) then 0 else clamp(38, 110, 72 * (HICP_current / HICP_base))
     *
     * At 2025-03-01T00:00:00Z:
     *   - EPEX_DA15_SETTLE = 24.86 (positive → gate does NOT fire)
     *   - HICP-DE current = 112.30, base = 108.70
     *   - Escalated price = 72.00 * (112.30 / 108.70) = 72.00 * 1.03311... = 74.384...
     *   - Clamped: max(38, min(110, 74.38)) = 74.38 (inside collar)
     *   - Active leaves: EPEX_DA15_GATE, FLOOR_38, CAP_110, BASE_PRICE_72, HICP_DE_CURRENT, HICP_DE_BASE_2023
     */
    @Test
    void cpiEscalatedCollarWithNegativePriceProtection() {
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

        var savedCells = new ArrayList<SettlementCell>();
        DomainEventPublisher eventPublisher = e -> {};

        // EXPR-4: 3-level CPI-escalated collar
        UUID escalatedCollarExprId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        SeriesKey seriesKey = new SeriesKey("VS-TEST-004");
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

        var job = new SettlementMaterializationJob(
            resolver, priceEvaluator, marketData, exprRepo,
            capturingCellRepo(savedCells), eventPublisher, new DefaultNumericPrecision(), NO_OP_INDEX);

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-7777")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(range)
            .quantity(BigDecimal.TEN)
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(escalatedCollarExprId)
            .volumeSeriesKey(seriesKey)
            .validFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .knownFrom(Instant.parse("2025-02-15T00:00:00Z"))
            .build();

        job.execute(position, range);

        assertEquals(1, savedCells.size());
        SettlementCell cell = savedCells.get(0);

        // Gate input: EPEX_DA15_SETTLE=24.86, condition "< 0" → false → proceed to inner
        // Escalated: 72.00 * (112.30 / 108.70) = 74.384... rounded to 8dp
        // Clamped: max(38, min(110, 74.38)) = 74.38 (inside collar)
        BigDecimal expectedEscalated = new BigDecimal("72.00")
            .multiply(new BigDecimal("112.30"))
            .divide(new BigDecimal("108.70"), 8, java.math.RoundingMode.HALF_UP);

        assertTrue(cell.price().compareTo(new BigDecimal("38")) >= 0,
            "Price should be at or above floor (38), got " + cell.price());
        assertTrue(cell.price().compareTo(new BigDecimal("110")) <= 0,
            "Price should be at or below cap (110), got " + cell.price());
        assertTrue(cell.price().subtract(expectedEscalated).abs()
            .compareTo(new BigDecimal("0.01")) < 0,
            "Price should be ~74.38 (CPI-escalated), got " + cell.price());

        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0,
            "Amount should be positive (gate did not fire)");

        // All leaves in the 3-level tree should be active
        assertTrue(cell.activeLeaves().contains("EPEX_DA15_GATE"),
            "Gate input leaf should be active");
        assertTrue(cell.activeLeaves().contains("BASE_PRICE_72"),
            "Base price leaf should be active");
        assertTrue(cell.activeLeaves().contains("HICP_DE_CURRENT"),
            "CPI index leaf should be active");
        assertTrue(cell.activeLeaves().contains("HICP_DE_BASE_2023"),
            "CPI base constant should be active");
    }
}
