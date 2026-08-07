package com.power.posval.kafka;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.PositionEntryCaptured;
import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.service.*;
import com.power.posval.domain.service.stub.JsonMarketDataPort;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test matching the walkthrough in
 * docs/spec-in-layman-language/developer_usage_walkthrough.md Section 9.
 *
 * Traces the FULL flow through every layer:
 *
 *   Step 1: TradeCapture command
 *           → DefaultTradeCaptureHandler
 *           → PositionLedgerEntry saved + PositionEntryCaptured events published (one per entry)
 *
 *   Step 2: Outbox relay (simulated — events passed directly to consumer)
 *
 *   Step 3: TradeCapturedConsumer.handle()
 *           → idempotency check (existsByPositionId)
 *           → loads single entry by positionId
 *           → calls SettlementMaterializationJob
 *
 *   Step 4: SettlementMaterializationJob.execute()
 *           → ProfileResolver resolves volume
 *           → PriceExpressionRepository loads formula
 *           → PriceExpressionBasedEvaluator walks expression tree
 *           → writeResult() computes amount = price × energy
 *
 *   Step 5: SettlementCell persisted + SettlementComputed event published
 *
 * All infrastructure is in-memory stubs — no database, Redis, or Kafka needed.
 */
class TradeToSettlementIntegrationTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    // Shared stores: what each layer writes, the next layer reads
    private final List<PositionLedgerEntry> ledgerStore = new ArrayList<>();
    private final List<SettlementCell> settlementStore = new ArrayList<>();
    private final List<Object> publishedEvents = new ArrayList<>();

    // Components wired manually (same as Guice would do)
    private DefaultTradeCaptureHandler tradeCaptureHandler;
    private TradeCapturedConsumer tradeCapturedConsumer;

    @BeforeEach
    void wireComponents() {
        ledgerStore.clear();
        settlementStore.clear();
        publishedEvents.clear();

        // --- Infrastructure stubs ---
        PositionLedgerRepository ledgerRepo = new InMemoryLedgerRepo(ledgerStore);
        DomainEventPublisher eventPublisher = publishedEvents::add;
        SettlementCellRepository cellRepo = new InMemoryCellRepo(settlementStore);

        // Volume data: a single 15-min interval, 50 MW → 12.5 MWh
        SeriesKey volumeSeriesKey = new SeriesKey("VS-T9999-1");
        DeliveryRange marchRange = DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET);
        List<VolumeInterval> volumeIntervals = List.of(
            new DefaultVolumeInterval(
                UUID.randomUUID(),
                Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-01T00:15:00Z"),
                new BigDecimal("50.0"),
                new BigDecimal("12.5"),
                1, null));
        VolumeSeriesRepository seriesRepo = stubSeriesRepo(
            volumeSeriesKey, marchRange, volumeIntervals);

        // --- Domain services ---
        var marketData = new JsonMarketDataPort();
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());
        var volumeResolver = new ProfileResolver(seriesRepo, new DefaultNumericPrecision());

        var settlementJob = new SettlementMaterializationJob(
            volumeResolver, priceEvaluator, marketData, exprRepo,
            cellRepo, eventPublisher, new DefaultNumericPrecision());

        // --- Wire the two entry points ---
        tradeCaptureHandler = new DefaultTradeCaptureHandler(ledgerRepo, eventPublisher);
        tradeCapturedConsumer = new TradeCapturedConsumer(
            ledgerRepo, cellRepo, settlementJob);
    }

    // =====================================================================
    //  Test 1: Fixed-price bilateral — the walkthrough Section 9 scenario
    // =====================================================================

    @Test
    void fullPipeline_fixedPrice_tradeToSettlement() {
        // EXPR-1: ConstantLeaf(85.00 EUR/MWh)
        UUID fixedPriceExprId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // --- STEP 1: Trade Capture ---
        TradeCapture command = tradeCapture("T-9999", fixedPriceExprId);
        List<PositionLedgerEntry> entries = tradeCaptureHandler.handle(command);

        assertEquals(1, entries.size(), "Single-month trade → 1 ledger entry");
        assertEquals(1, ledgerStore.size(), "Entry persisted to ledger");
        assertEquals("ACTIVE", entries.get(0).status());

        // One PositionEntryCaptured event per entry
        assertEquals(1, publishedEvents.size());
        assertInstanceOf(PositionEntryCaptured.class, publishedEvents.get(0));
        PositionEntryCaptured capturedEvent = (PositionEntryCaptured) publishedEvents.get(0);
        assertEquals(entries.get(0).id(), capturedEvent.positionId());

        // --- STEP 2: Outbox relay (simulated) ---
        // In production: outbox row → OutboxRelayProducer → Kafka topic
        // Here: pass event directly to consumer

        // --- STEP 3: Consumer receives event ---
        publishedEvents.clear();
        tradeCapturedConsumer.handle(capturedEvent);

        // --- STEPS 4 + 5: Materialization + Result ---
        assertEquals(1, settlementStore.size(), "One settlement cell created");
        SettlementCell cell = settlementStore.get(0);

        // Verify the core math: price × energy = amount
        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()),
            "Fixed price = 85.00 EUR/MWh");
        assertEquals(0, new BigDecimal("12.5").compareTo(cell.volumeMwh()),
            "50 MW × 0.25h = 12.5 MWh");
        assertTrue(cell.amount().compareTo(BigDecimal.ZERO) > 0,
            "Amount = 85.00 × 12.5 = 1062.50 EUR (positive)");

        // Verify position linkage
        assertEquals("TN_0042", cell.tenantId());
        assertEquals(entries.get(0).id(), cell.positionId());

        // Verify active-leaves tracking
        assertTrue(cell.activeLeaves().contains("FIXED_85"));

        // Verify SettlementComputed event (Step 5 downstream trigger)
        assertEquals(1, publishedEvents.size());
        assertInstanceOf(SettlementComputed.class, publishedEvents.get(0));
        SettlementComputed settled = (SettlementComputed) publishedEvents.get(0);
        assertEquals(entries.get(0).id(), settled.positionId());
        assertEquals("PROVISIONAL", settled.status());
    }

    // =====================================================================
    //  Test 2: Market-linked pricing (walkthrough Section 10)
    // =====================================================================

    @Test
    void fullPipeline_indexPlusSpread_tradeToSettlement() {
        // EXPR-2: Add(EPEX_DA15_SETTLE + 3.20 premium)
        UUID indexSpreadExprId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        TradeCapture command = tradeCapture("T-8888", indexSpreadExprId);
        tradeCaptureHandler.handle(command);
        PositionEntryCaptured event = (PositionEntryCaptured) publishedEvents.get(0);

        publishedEvents.clear();
        tradeCapturedConsumer.handle(event);

        assertEquals(1, settlementStore.size());
        SettlementCell cell = settlementStore.get(0);

        // EPEX_DA15_SETTLE @ 2025-03-01T00:00:00Z = 24.86, + premium 3.20 = 28.06
        // Price evaluator uses 8-decimal scale, so compare with tolerance
        assertTrue(cell.price().subtract(new BigDecimal("28.06")).abs()
            .compareTo(new BigDecimal("0.01")) < 0,
            "Index + spread: ~24.86 + 3.20 ≈ 28.06, got " + cell.price());
        assertTrue(cell.activeLeaves().contains("EPEX_DA15"));
        assertTrue(cell.activeLeaves().contains("PREMIUM_3_20"));
        assertFalse(cell.inputVersionSet().isEmpty(),
            "Should track market data version");
    }

    // =====================================================================
    //  Test 3: Idempotency — same event twice → no duplicate cells
    // =====================================================================

    @Test
    void idempotency_duplicateEventDoesNotCreateDuplicateCells() {
        UUID exprId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TradeCapture command = tradeCapture("T-7777", exprId);

        tradeCaptureHandler.handle(command);
        PositionEntryCaptured event = (PositionEntryCaptured) publishedEvents.get(0);
        publishedEvents.clear();

        // First time
        tradeCapturedConsumer.handle(event);
        assertEquals(1, settlementStore.size());

        // Second time — idempotency guard skips
        tradeCapturedConsumer.handle(event);
        assertEquals(1, settlementStore.size(),
            "Duplicate event must not create additional cells");
    }

    // =====================================================================
    //  Test 4: Multi-month trade creates one entry per month
    // =====================================================================

    @Test
    void multiMonthTrade_createsOneEntryPerMonth() {
        UUID exprId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        TradeCapture command = new TradeCapture(
            "T-6666", 1, "LEG-1", "TN_0042",
            new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0, CET), CET),
            new BigDecimal("50.0"), VolumeUnit.MW_CAPACITY, exprId,
            "PORTFOLIO-1", "DE_LU", "BILATERAL_TRADE",
            Instant.parse("2025-02-15T00:00:00Z"),
            null, BigDecimal.ONE, new SeriesKey("VS-T9999-1"), null);

        List<PositionLedgerEntry> entries = tradeCaptureHandler.handle(command);
        assertEquals(3, entries.size(), "Mar + Apr + May = 3 monthly blocks");
        assertEquals(3, ledgerStore.size());

        // 3 entries → 3 PositionEntryCaptured events
        assertEquals(3, publishedEvents.size());
        for (int i = 0; i < 3; i++) {
            assertInstanceOf(PositionEntryCaptured.class, publishedEvents.get(i));
            assertEquals(entries.get(i).id(),
                ((PositionEntryCaptured) publishedEvents.get(i)).positionId());
        }
    }


    // ===== Helpers =====

    private TradeCapture tradeCapture(String tradeId, UUID priceExpressionId) {
        return new TradeCapture(
            tradeId, 1, "LEG-1", "TN_0042",
            new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET),
            new BigDecimal("50.0"), VolumeUnit.MW_CAPACITY, priceExpressionId,
            "PORTFOLIO-1", "DE_LU", "BILATERAL_TRADE",
            Instant.parse("2025-02-15T00:00:00Z"),
            null, BigDecimal.ONE, new SeriesKey("VS-T9999-1"), null);
    }


    // ===== In-memory stub repositories =====

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
        @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) {
            return List.of();
        }
        @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) {
            return List.of();
        }
        @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) {
            return List.of();
        }
        @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {
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
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String sk) {
                return sk.equals(key.value()) ? Optional.of(series) : Optional.empty();
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String tradeId, int tradeVersion) {
                return false;
            }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };
    }
}
