package com.power.posval.kafka;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.*;
import com.power.posval.domain.service.DefaultPriceEvaluator;
import com.power.posval.domain.service.DefaultTradeCaptureHandler;
import com.power.posval.domain.service.ProfileResolver;
import com.power.posval.domain.service.SettlementMaterializationJob;
import com.power.posval.domain.service.stub.JsonMarketDataPort;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full pipeline integration test for a 5-year wind PPA at 15-minute granularity.
 *
 * Scenario: Onshore wind park "Nordsee-01", 80 MW capacity
 *   - Delivery: Jan 2025 to Dec 2029 (60 monthly blocks, ~175,200 intervals)
 *   - Price: EXPR-4 — CPI-escalated collar with negative-price protection
 *   - Volume: 15-min profile with simulated wind generation pattern
 *
 * Market data and price expressions are loaded from the JSON stub files
 * (stub/market-data.json and stub/price-expressions.json) via
 * JsonMarketDataPort and JsonPriceExpressionRepository. These load JSON
 * into in-memory HashMaps at construction time — all lookups during the
 * test are pure HashMap.get() calls with zero I/O.
 *
 * The JSON fixings cover representative days (1 day per quarter across 5 years
 * = ~2,200 intervals). For the ~173,000 intervals without explicit fixings,
 * JsonMarketDataPort returns BigDecimal.ZERO. This is correct for EXPR-4
 * because:
 *   - The gate checks "EPEX < 0" — zero is NOT negative, so the gate passes
 *   - The price comes from the Escalate(72 * HICP/base) chain, not the fixing
 *   - Only the gate leaf reads the fixing; the collar+escalation is independent
 *
 * Forward curves span all 60 months (Jan 2025 - Dec 2029) with seasonal
 * pattern loaded from JSON.
 */
class FiveYearTradeIntegrationTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final UUID EXPR_4_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final SeriesKey SERIES_KEY = new SeriesKey("VS-WP-NORDSEE-5YR");

    private final List<PositionLedgerEntry> ledgerStore = new ArrayList<>();
    private final List<SettlementCell> settlementStore = new ArrayList<>(180000);
    private final List<Object> publishedEvents = new ArrayList<>();

    private DefaultTradeCaptureHandler tradeCaptureHandler;
    private TradeCapturedConsumer tradeCapturedConsumer;
    private int totalIntervalCount;

    @BeforeEach
    void wireComponents() {
        ledgerStore.clear();
        settlementStore.clear();
        publishedEvents.clear();

        ZonedDateTime start = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET);
        ZonedDateTime end = ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, CET);

        // --- Generate 5 years of 15-min volume intervals ---
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(42);
        ZonedDateTime cursor = start;
        int seq = 0;
        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(15);
            // Wind pattern: higher at night (offshore characteristic), 20-70 MW range
            double hourBase = 45.0 + 20.0 * Math.sin(cursor.getHour() * Math.PI / 12.0);
            double mw = Math.max(0, Math.min(80, hourBase + random.nextGaussian() * 8.0));
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(new BigDecimal("0.25"))
                .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                new UUID(0L, ++seq),
                cursor.toInstant(), next.toInstant(),
                volume, energy, 1, null));
            cursor = next;
        }
        totalIntervalCount = intervals.size();

        // --- Volume series covering full 5 years ---
        VolumeSeries series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(SERIES_KEY)
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(new DeliveryPeriod(start, end, CET))
            .qualityState(QualityState.EFFECTIVE)
            .transactionTime(Instant.now())
            .intervals(intervals)
            .build();

        VolumeSeriesRepository seriesRepo = new VolumeSeriesRepository() {
            final Set<String> processed = new HashSet<>();
            @Override public void save(VolumeSeries s) {}
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String t, String sk) {
                return sk.equals(SERIES_KEY.value()) ? Optional.of(series) : Optional.empty();
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String tid, int tv) {
                return !processed.add(tid + ":" + tv);
            }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };

        // --- Market data from JSON stubs (loaded into memory at construction) ---
        var marketData = new JsonMarketDataPort();

        // --- Price expressions from JSON stubs (EXPR-4 is in the file) ---
        var exprRepo = new JsonPriceExpressionRepository();

        // --- Infrastructure stubs ---
        PositionLedgerRepository ledgerRepo = new InMemoryLedgerRepo(ledgerStore);
        SettlementCellRepository cellRepo = new InMemoryCellRepo(settlementStore);
        DomainEventPublisher eventPublisher = publishedEvents::add;

        // --- Wire domain services ---
        var priceEvaluator = new DefaultPriceEvaluator(new DefaultNumericPrecision());
        var volumeResolver = new ProfileResolver(seriesRepo, new DefaultNumericPrecision());
        var settlementJob = new SettlementMaterializationJob(
            volumeResolver, priceEvaluator, marketData, exprRepo,
            cellRepo, eventPublisher, new DefaultNumericPrecision());

        tradeCaptureHandler = new DefaultTradeCaptureHandler(ledgerRepo, eventPublisher);
        tradeCapturedConsumer = new TradeCapturedConsumer(
            ledgerRepo, cellRepo, settlementJob);
    }

    @Test
    void fiveYearPpa_fullPipeline_captureToSettlement() {
        long start = System.currentTimeMillis();
        // --- STEP 1: Capture 5-year trade ---
        TradeCapture command = new TradeCapture(
            "T-5YR-001", 1, "LEG-1", "TN_0042",
            new DeliveryPeriod(
                ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, CET), CET),
            new BigDecimal("80.0"), VolumeUnit.MW_CAPACITY, EXPR_4_ID,
            "PORTFOLIO-WIND", "DE_LU", "PPA_ONSHORE",
            Instant.parse("2024-12-01T00:00:00Z"),
            "ASSET-WP-NORDSEE-01", BigDecimal.ONE, SERIES_KEY, null);

        List<PositionLedgerEntry> entries = tradeCaptureHandler.handle(command);

        // 5 years = 60 monthly blocks
        assertEquals(60, entries.size(), "5-year trade -> 60 monthly ledger entries");
        assertEquals(60, ledgerStore.size());

        // Verify first and last month
        assertEquals(YearMonth.of(2025, 1), entries.get(0).deliveryRange().startMonth());
        assertEquals(YearMonth.of(2029, 12), entries.get(59).deliveryRange().startMonth());

        // --- STEP 2+3: Consumer processes all 60 months ---
        PositionCaptured capturedEvent = (PositionCaptured) publishedEvents.get(0);
        assertEquals(60, capturedEvent.entryCount());

        publishedEvents.clear();
        tradeCapturedConsumer.handle(capturedEvent);

        // --- STEP 4+5: Verify settlement cells ---
        assertEquals(totalIntervalCount, settlementStore.size(),
            "One settlement cell per 15-min interval across 5 years. " +
            "Expected ~175,200, got " + settlementStore.size());

        // EXPR-4 gate condition is "< 0". JsonMarketDataPort returns 0.0 for
        // missing fixings, and positive values for fixings that exist in the
        // JSON. Zero is NOT < 0, so the gate never fires. Every cell should
        // have the CPI-escalated price (72 * 112.30/108.70 ≈ 74.38), clamped
        // to [38, 110].
        long zeroPriceCells = settlementStore.stream()
            .filter(c -> c.price().compareTo(BigDecimal.ZERO) == 0)
            .count();
        assertEquals(0, zeroPriceCells,
            "No cells should have zero price — gate never fires, escalated price always > 0");

        // All prices should be within collar bounds [38, 110]
        BigDecimal floor = new BigDecimal("38");
        BigDecimal cap = new BigDecimal("110");
        for (SettlementCell cell : settlementStore) {
            assertTrue(cell.price().compareTo(floor) >= 0,
                "Price " + cell.price() + " below floor 38 at " + cell.intervalStart());
            assertTrue(cell.price().compareTo(cap) <= 0,
                "Price " + cell.price() + " above cap 110 at " + cell.intervalStart());
        }

        // Since CPI escalation is constant (112.30/108.70), all cells should
        // have the same price: 72 * (112.30/108.70) ≈ 74.38
        BigDecimal expectedPrice = settlementStore.get(0).price();
        long distinctPrices = settlementStore.stream()
            .map(SettlementCell::price)
            .distinct()
            .count();
        assertEquals(1, distinctPrices,
            "All cells should have identical CPI-escalated price since HICP ratio is constant");

        // Amounts: non-negative (zero when wind = 0 MW)
        long negativeAmounts = settlementStore.stream()
            .filter(c -> c.amount().compareTo(BigDecimal.ZERO) < 0)
            .count();
        assertEquals(0, negativeAmounts, "No amounts should be negative");

        // 99%+ should have positive amounts (wind rarely drops to exactly 0)
        long positiveAmounts = settlementStore.stream()
            .filter(c -> c.amount().compareTo(BigDecimal.ZERO) > 0)
            .count();
        assertTrue(positiveAmounts > totalIntervalCount * 0.99,
            "At least 99% of cells should have positive amounts, got " +
            positiveAmounts + " / " + totalIntervalCount);
        long end = System.currentTimeMillis();
        System.out.println("Total time of exec = " + (end - start)/1000);
        // Verify active leaves include the CPI escalation chain.
        // Note: FLOOR_38 and CAP_110 are NOT active when the price is inside
        // the collar (74.38 is between 38 and 110). The Clamp evaluator only
        // adds bound leaves when clamping actually changes the value — this is
        // the "active leaves optimization" (D-4) that reduces revaluation
        // blast radius.
        SettlementCell sampleCell = settlementStore.get(0);
        assertTrue(sampleCell.activeLeaves().contains("EPEX_DA15_GATE"),
            "Gate input leaf should be tracked");
        assertTrue(sampleCell.activeLeaves().contains("BASE_PRICE_72"),
            "Escalation base should be tracked");
        assertTrue(sampleCell.activeLeaves().contains("HICP_DE_CURRENT"),
            "CPI index lookup should be tracked");
        assertTrue(sampleCell.activeLeaves().contains("HICP_DE_BASE_2023"),
            "CPI base constant should be tracked");
        // Floor and cap leaves are INACTIVE when price is inside the collar
        assertTrue(!sampleCell.activeLeaves().contains("FLOOR_38"),
            "Floor leaf should NOT be active — price 74.38 is above floor 38");
        assertTrue(!sampleCell.activeLeaves().contains("CAP_110"),
            "Cap leaf should NOT be active — price 74.38 is below cap 110");

        // Verify SettlementComputed events published (one per cell)
        assertEquals(totalIntervalCount, publishedEvents.size(),
            "One SettlementComputed event per cell");
        assertTrue(publishedEvents.stream().allMatch(e -> e instanceof SettlementComputed));
    }

    @Test
    void fiveYearPpa_verifyJsonForwardCurveCoverage() {
        // Verify the JSON stub has forward curves spanning all 60 months
        var md = new JsonMarketDataPort();

        // Winter 2029 should be expensive (heating demand)
        var winter2029 = md.lookupForwardCurve("EEX_BASE_DE",
            YearMonth.of(2029, 1), Instant.parse("2025-02-28T18:00:00Z"));
        assertTrue(winter2029.value().compareTo(new BigDecimal("80")) > 0,
            "Winter 2029 forward should be >80 EUR/MWh, got " + winter2029.value());

        // Summer 2029 should be cheap (solar surplus)
        var summer2029 = md.lookupForwardCurve("EEX_BASE_DE",
            YearMonth.of(2029, 7), Instant.parse("2025-02-28T18:00:00Z"));
        assertTrue(summer2029.value().compareTo(new BigDecimal("65")) < 0,
            "Summer 2029 forward should be <65 EUR/MWh, got " + summer2029.value());

        // Seasonal pattern: winter > summer
        assertTrue(winter2029.value().compareTo(summer2029.value()) > 0,
            "Winter (" + winter2029.value() + ") should exceed summer (" +
            summer2029.value() + ")");
    }

    @Test
    void fiveYearPpa_verifyJsonFixingsExistForRepresentativeDays() {
        // Verify the JSON has fixings across the 5-year horizon
        var md = new JsonMarketDataPort();

        // March 2025 — original detailed data (3 full days)
        var mar2025 = md.lookupFixing("EPEX_DA15", Instant.parse("2025-03-01T00:00:00Z"));
        assertTrue(mar2025.value().compareTo(BigDecimal.ZERO) > 0,
            "March 2025 fixing should be present and positive");

        // January 2027 — representative quarterly day
        var jan2027 = md.lookupFixing("EPEX_DA15", Instant.parse("2027-01-01T00:00:00Z"));
        assertTrue(jan2027.value().compareTo(BigDecimal.ZERO) > 0,
            "Jan 2027 fixing should be present from quarterly representative data");

        // October 2029 — far end of 5-year horizon
        var oct2029 = md.lookupFixing("EPEX_DA15", Instant.parse("2029-10-01T12:00:00Z"));
        assertTrue(oct2029.value().compareTo(BigDecimal.ZERO) > 0,
            "Oct 2029 fixing should be present from quarterly representative data");

        // Missing date returns zero (not an error)
        var missing = md.lookupFixing("EPEX_DA15", Instant.parse("2028-06-15T08:30:00Z"));
        assertEquals(0, BigDecimal.ZERO.compareTo(missing.value()),
            "Non-representative date should return zero");
    }


    // ===== In-memory stubs for ledger and cells =====

    private static class InMemoryLedgerRepo implements PositionLedgerRepository {
        private final List<PositionLedgerEntry> store;
        InMemoryLedgerRepo(List<PositionLedgerEntry> store) { this.store = store; }

        @Override public void save(PositionLedgerEntry entry) { store.add(entry); }
        @Override public Optional<PositionLedgerEntry> findById(UUID id) {
            return store.stream().filter(e -> e.id().equals(id)).findFirst();
        }
        @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(
                String tenantId, String tradeId, String tradeLegId) {
            return store.stream()
                .filter(e -> e.tenantId().equals(tenantId)
                    && e.tradeId().equals(tradeId)
                    && e.tradeLegId().equals(tradeLegId)
                    && "ACTIVE".equals(e.status()))
                .toList();
        }
        @Override public List<PositionLedgerEntry> findAsOf(String t, String tr,
                                                             String tl, Instant b, Instant k) {
            return List.of();
        }
        @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t,
                                                                          Instant s, Instant e) {
            return List.of();
        }
        @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t,
                                                                                    String tr, String tl,
                                                                                    Instant s, Instant e) {
            return List.of();
        }
        @Override public void supersede(List<PositionLedgerEntry> old,
                                         List<PositionLedgerEntry> nw) {
            nw.forEach(store::add);
        }
    }

    private static class InMemoryCellRepo implements SettlementCellRepository {
        private final List<SettlementCell> store;
        InMemoryCellRepo(List<SettlementCell> store) { this.store = store; }

        @Override public void save(SettlementCell cell) { store.add(cell); }
        @Override public List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                                              Instant rangeStart, Instant rangeEnd) {
            return store.stream()
                .filter(c -> c.tenantId().equals(tenantId) && c.positionId().equals(positionId))
                .toList();
        }
    }
}
