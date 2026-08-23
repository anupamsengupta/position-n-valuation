package com.power.posval.domain.service.da;

import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.command.ImportAuctionResults;
import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.AuctionImportCompleted;
import com.power.posval.domain.event.AuctionImportFailed;
import com.power.posval.domain.event.OperationalAlertRaised;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;
import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;
import com.power.posval.domain.model.HolidayCalendar;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.port.AuctionResultParser;
import com.power.posval.domain.port.MarketCalendarPort;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import com.power.posval.domain.port.repository.AuctionImportSessionRepository;
import com.power.posval.domain.port.repository.BlockDefinitionRepository;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.service.BlockDecompositionService;
import com.power.posval.domain.port.service.OperationalAlertService;
import com.power.posval.domain.service.TradeCaptureHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultAuctionImportOrchestrator}.
 *
 * <p>Covers the 8-step DA auction import pipeline (S8.1, DA-VOL-01).
 * All ports are hand-mocked with anonymous implementations or simple stubs.
 * Pattern #18, S8.1, DA-VOL-01, DA-PRC-01, DA-OPS-01.
 */
class AuctionImportOrchestratorTest {

    private static final String TENANT     = "TN_0042";
    private static final String EXCHANGE   = "EPEX_SPOT";
    private static final String ZONE       = "DE_LU";
    private static final LocalDate DAY     = LocalDate.of(2026, 9, 16);
    private static final ZoneId CET        = ZoneId.of("Europe/Berlin");

    /** CET midnight for the delivery day. */
    private static final Instant DAY_START = DAY.atStartOfDay(CET).toInstant();

    // Mutable state captured by stubs
    private final List<AuctionImportSession> savedSessions   = new ArrayList<>();
    private final List<Object>               publishedEvents = new ArrayList<>();
    private final List<OperationalAlert>     raisedAlerts    = new ArrayList<>();
    private final List<TradeCapture>         capturedTrades  = new ArrayList<>();

    /** Simulate existing IMPORTED session for idempotency tests. */
    private AuctionImportSession existingImportedSession = null;

    private DefaultAuctionImportOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        savedSessions.clear();
        publishedEvents.clear();
        raisedAlerts.clear();
        capturedTrades.clear();
        existingImportedSession = null;

        // ---- Stub: AuctionImportSessionRepository ----
        AuctionImportSessionRepository sessionRepo = new AuctionImportSessionRepository() {
            @Override public void save(AuctionImportSession s) { savedSessions.add(s); }
            @Override public Optional<AuctionImportSession> findBySessionId(String t, UUID id) {
                return savedSessions.stream().filter(s -> s.sessionId().equals(id)).findFirst();
            }
            @Override public Optional<AuctionImportSession> findByDeliveryDay(String t, String ex,
                    String zone, LocalDate d) {
                if (existingImportedSession != null
                        && existingImportedSession.deliveryDay().equals(d)) {
                    return Optional.of(existingImportedSession);
                }
                return Optional.empty();
            }
            @Override public void updateStatus(String t, UUID id, AuctionImportStatus s, Instant at) {}
            @Override public List<AuctionImportSession> findByExchangeAndBiddingZone(String t,
                    String ex, String zone) {
                return List.copyOf(savedSessions);
            }
        };

        // ---- Stub: AuctionResultParser (not used by importAuctionResults directly) ----
        AuctionResultParser parser = new AuctionResultParser() {
            @Override public AuctionResultBatch parse(java.nio.file.Path f) { throw new UnsupportedOperationException(); }
            @Override public String supportedFormat() { return "EPEX_DA_CSV_V1"; }
        };

        // ---- Stub: MarketCalendarPort — returns 96 intervals for standard day ----
        MarketCalendarPort calendarPort = new MarketCalendarPort() {
            @Override public List<DeliveryPeriod> intervalsForDay(String zone, LocalDate day,
                    TimeGranularity g) {
                List<DeliveryPeriod> slots = new ArrayList<>();
                ZonedDateTime cursor = day.atStartOfDay(CET);
                ZonedDateTime end    = day.plusDays(1).atStartOfDay(CET);
                while (cursor.isBefore(end)) {
                    ZonedDateTime next = cursor.plusMinutes(15);
                    slots.add(new DeliveryPeriod(cursor, next, CET));
                    cursor = next;
                }
                return slots;
            }
            @Override public int intervalCount(String zone, LocalDate day, TimeGranularity g) {
                return intervalsForDay(zone, day, g).size();
            }
            @Override public boolean isPeakInterval(String zone, DeliveryPeriod p) { return false; }
            @Override public DeliveryPeriod deliveryDayBoundaries(String zone, LocalDate day) {
                return new DeliveryPeriod(day.atStartOfDay(CET), day.plusDays(1).atStartOfDay(CET), CET);
            }
        };

        // ---- Stub: BlockDecompositionService — non-block contracts pass through ----
        BlockDecompositionService blockService = (contract, def, day) -> {
            if (contract.blockType() == null || contract.blockType().isBlank()) {
                return List.of(contract);
            }
            // Baseload block: decompose into slots
            List<AuctionResultContract> slots = new ArrayList<>();
            ZonedDateTime cursor = day.atStartOfDay(CET);
            ZonedDateTime end    = day.plusDays(1).atStartOfDay(CET);
            int idx = 0;
            while (cursor.isBefore(end)) {
                idx++;
                ZonedDateTime next = cursor.plusMinutes(15);
                BigDecimal vol = contract.volumeMw().multiply(contract.executionRatio());
                slots.add(new AuctionResultContract(
                    contract.contractId() + "/interval-" + idx,
                    contract.priceMwh(), vol,
                    cursor.toInstant(), next.toInstant(),
                    null, BigDecimal.ONE, contract.direction()
                ));
                cursor = next;
            }
            return List.copyOf(slots);
        };

        // ---- Stub: TradeCaptureHandler ----
        TradeCaptureHandler tradeHandler = cmd -> {
            capturedTrades.add(cmd);
            return List.of(makeLedgerEntry(cmd));
        };

        // ---- Stub: PriceExpressionRepository ----
        PriceExpressionRepository priceExprRepo = new PriceExpressionRepository() {
            @Override public Optional<PriceExpression> findById(UUID id) { return Optional.empty(); }
            @Override public void save(UUID id, PriceExpression expr) {}
        };

        // ---- Stub: MarketDataRepository ----
        MarketDataRepository mdRepo = new MarketDataRepository() {
            @Override public Optional<MarketDataLookup> findFixing(String t, String s, Instant i) { return Optional.empty(); }
            @Override public Optional<MarketDataLookup> findIndex(String t, String s, String r) { return Optional.empty(); }
            @Override public Optional<MarketDataLookup> findForwardCurve(String t, String s, YearMonth p, Instant a) { return Optional.empty(); }
            @Override public Optional<MarketDataLookup> findFxRate(String t, String c, Instant r) { return Optional.empty(); }
            @Override public Optional<MarketDataLookup> findSpread(String t, String s, Instant i) { return Optional.empty(); }
            @Override public Optional<VolSurfaceLookup> findVolSurface(String t, String s, double d, String e, Instant a) { return Optional.empty(); }
            @Override public Optional<MarketDataLookup> findAtVersion(String t, String s, Instant i, long v) { return Optional.empty(); }
            @Override public void saveFixing(String t, String s, Instant i, MarketDataLookup l) {}
            @Override public void saveForwardCurve(String t, String s, YearMonth p, Instant a, MarketDataLookup l) {}
            @Override public void saveFxRate(String t, String c, Instant r, MarketDataLookup l) {}
            @Override public void saveIndex(String t, String s, String r, MarketDataLookup l) {}
            @Override public void saveSpread(String t, String s, Instant i, MarketDataLookup l) {}
            @Override public void saveVolSurface(String t, String s, double d, String e, Instant a, VolSurfaceLookup v) {}
        };

        // ---- Stub: OperationalAlertService ----
        OperationalAlertRepository alertRepo = new OperationalAlertRepository() {
            @Override public void save(OperationalAlert a) { raisedAlerts.add(a); }
            @Override public List<OperationalAlert> findOpen(String t) { return List.of(); }
            @Override public List<OperationalAlert> findByCategory(String t, AlertCategory c, AlertStatus s) { return List.of(); }
            @Override public List<OperationalAlert> findByDeliveryDay(String t, LocalDate d) { return List.of(); }
            @Override public void acknowledge(String t, UUID id, String u, Instant at) {}
            @Override public void resolve(String t, UUID id, Instant at) {}
            @Override public Map<AlertCategory, Long> countByStatus(String t, AlertStatus s) { return Map.of(); }
        };
        OperationalAlertService alertService =
            new DefaultOperationalAlertService(alertRepo, publishedEvents::add);

        // ---- Stub: BlockDefinitionRepository ----
        BlockDefinitionRepository blockDefRepo = new BlockDefinitionRepository() {
            @Override public Optional<BlockDefinition> findEffective(String ex, BlockType bt, String zone, LocalDate d) { return Optional.empty(); }
            @Override public List<BlockDefinition> findAllEffective(String ex, LocalDate d) { return List.of(); }
        };

        DomainEventPublisher publisher = publishedEvents::add;

        // ---- Stub: VolumeSeriesRepository ----
        VolumeSeriesRepository volumeSeriesRepo = new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) {}
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String t, String k) { return Optional.empty(); }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String id, int v) { return false; }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };

        orchestrator = new DefaultAuctionImportOrchestrator(
            sessionRepo, parser, calendarPort, blockService, tradeHandler,
            priceExprRepo, mdRepo, alertService, publisher, blockDefRepo,
            volumeSeriesRepo);
    }

    // ---------------------------------------------------------------------------
    // Happy path: valid batch → IMPORTED, correct trade count
    // ---------------------------------------------------------------------------

    @Test
    void happyPath_validBatch_sessionImportedWithCorrectTradeCount() {
        // 96 contracts × 1 MW × 0.25 h (15-min interval) = 24.0 MWh
        AuctionResultBatch batch = batchOf96Contracts(new BigDecimal("24.0"));
        ImportAuctionResults cmd = new ImportAuctionResults(TENANT, batch);

        AuctionImportSession session = orchestrator.importAuctionResults(cmd);

        assertEquals(AuctionImportStatus.IMPORTED, session.status());
        assertEquals(96, session.tradeIds().size());
        assertNotNull(session.completedAt());
    }

    @Test
    void happyPath_publishesAuctionImportCompletedEvent() {
        AuctionResultBatch batch = batchOf96Contracts(new BigDecimal("24.0"));
        orchestrator.importAuctionResults(new ImportAuctionResults(TENANT, batch));

        assertTrue(publishedEvents.stream().anyMatch(e -> e instanceof AuctionImportCompleted),
            "Expected AuctionImportCompleted event");
    }

    // ---------------------------------------------------------------------------
    // Idempotency: re-import same delivery day returns existing IMPORTED session
    // ---------------------------------------------------------------------------

    @Test
    void idempotency_reImportSameDay_returnsExistingSession() {
        existingImportedSession = buildImportedSession();

        AuctionResultBatch batch = batchOf96Contracts(new BigDecimal("24.0"));
        AuctionImportSession result = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertSame(existingImportedSession, result,
            "Should return the existing IMPORTED session without re-importing");
        // No trade capture should occur
        assertTrue(capturedTrades.isEmpty(), "No new trades should be captured on re-import");
    }

    // ---------------------------------------------------------------------------
    // Validation failure: interval count exceeds expected (possible duplicates)
    // ---------------------------------------------------------------------------

    @Test
    void validationFailure_tooManyIntervals_sessionValidationFailed() {
        // 97 spot contracts but market calendar returns 96 intervals — over-count
        List<AuctionResultContract> contracts = new ArrayList<>();
        for (int i = 0; i < 97; i++) {
            contracts.add(spotContract("C-" + String.format("%04d", i),
                new BigDecimal("45.0"), new BigDecimal("1.0")));
        }
        AuctionResultBatch batch = new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, BigDecimal.ZERO, "test.csv", contracts
        );

        AuctionImportSession session = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertEquals(AuctionImportStatus.VALIDATION_FAILED, session.status());
        assertFalse(session.validationErrors().isEmpty());
        assertTrue(capturedTrades.isEmpty(), "No trades should be captured on validation failure");
    }

    @Test
    void validationFailure_emptyBatch_publishesAuctionImportFailedEvent() {
        AuctionResultBatch batch = new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, BigDecimal.ZERO, "test.csv",
            List.of()
        );

        orchestrator.importAuctionResults(new ImportAuctionResults(TENANT, batch));

        assertTrue(publishedEvents.stream().anyMatch(e -> e instanceof AuctionImportFailed),
            "Expected AuctionImportFailed event on validation failure");
    }

    @Test
    void partialImport_fewerThanExpectedIntervals_succeeds() {
        // Only 1 spot contract (partial import) — should succeed with relaxed validation
        AuctionResultBatch batch = new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, BigDecimal.ZERO, "test.csv",
            List.of(spotContract("C-0001", new BigDecimal("45.0"), new BigDecimal("1.0")))
        );

        AuctionImportSession session = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertEquals(AuctionImportStatus.IMPORTED, session.status(),
            "Partial imports should succeed — fills may arrive incrementally");
        assertEquals(1, capturedTrades.size());
    }

    // ---------------------------------------------------------------------------
    // Validation failure: MWh mismatch
    // ---------------------------------------------------------------------------

    @Test
    void validationFailure_mwhMismatch_sessionValidationFailed() {
        // 96 contracts, each 1 MW over 15 min = 0.25 MWh each, total = 24 MWh
        // But exchangeReportedTotalMwh = 9999 → mismatch > 1%
        AuctionResultBatch batch = batchOf96Contracts(new BigDecimal("9999.00"));

        AuctionImportSession session = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertEquals(AuctionImportStatus.VALIDATION_FAILED, session.status());
        assertTrue(session.validationErrors().stream()
            .anyMatch(e -> e.contains("reconciliation")),
            "Expected volume reconciliation error message");
    }

    // ---------------------------------------------------------------------------
    // Block order decomposition: block contract expanded into intervals
    // ---------------------------------------------------------------------------

    @Test
    void blockOrderDecomposition_expandedContractsProduceTrades() {
        // A BASELOAD block alongside 96 spot contracts.
        // The orchestrator validates: spotContractCount (96) <= expectedIntervals (96) → passes.
        // Then decomposition expands the BASELOAD block to 96 more intervals.
        // Total trade captures = 96 (spots) + 96 (block expanded) = 192.
        AuctionResultContract block = new AuctionResultContract(
            "BLOCK-001", new BigDecimal("44.0"), new BigDecimal("100.0"),
            DAY_START, DAY.plusDays(1).atStartOfDay(CET).toInstant(),
            "BASELOAD", BigDecimal.ONE, TradeDirection.BUY
        );
        // 96 spot contracts: volume 1 MW each, 24 MWh total. Block excluded from MWh reconciliation.
        List<AuctionResultContract> contracts = new ArrayList<>(build96Contracts(
            new BigDecimal("45.0"), new BigDecimal("1.0")));
        contracts.add(block);

        // exchangeReportedTotalMwh is based on spot contracts only: 96 * 1 MW * 0.25 h = 24 MWh
        AuctionResultBatch batch = new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, new BigDecimal("24.0"), "test.csv", contracts);

        AuctionImportSession session = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertEquals(AuctionImportStatus.IMPORTED, session.status());
        // 96 spots + 96 expanded from block = 192 trade captures
        assertEquals(192, capturedTrades.size(),
            "96 spot contracts + BASELOAD block (expanded to 96 intervals) = 192 trades");
    }

    // ---------------------------------------------------------------------------
    // Price plausibility: negative price raises WARNING alert
    // ---------------------------------------------------------------------------

    @Test
    void negativeClearingPrice_raisesInfoAlert() {
        // Use a batch where price is negative — we still import, but an alert is raised
        // 96 contracts × 10 MW × 0.25 h = 240.0 MWh (must match exchangeReportedTotalMwh)
        List<AuctionResultContract> contracts = build96Contracts(new BigDecimal("-50.0"), new BigDecimal("10.0"));
        AuctionResultBatch batch = new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, new BigDecimal("240.0"), "test.csv", contracts);

        AuctionImportSession session = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertEquals(AuctionImportStatus.IMPORTED, session.status());
        // INFO alert raised for negative price (at least one per negative-price contract)
        assertTrue(publishedEvents.stream()
            .filter(e -> e instanceof OperationalAlertRaised)
            .map(e -> (OperationalAlertRaised) e)
            .anyMatch(e -> e.category() == AlertCategory.DA_CLEARING_PRICES),
            "Expected DA_CLEARING_PRICES alert for negative price");
    }

    // ---------------------------------------------------------------------------
    // Price plausibility: extreme price (>4000) raises WARNING alert
    // ---------------------------------------------------------------------------

    @Test
    void extremeClearingPrice_raisesWarningAlert() {
        // 96 × 10 MW × 0.25 h = 240.0 MWh (must match exchangeReportedTotalMwh)
        List<AuctionResultContract> contracts = build96Contracts(new BigDecimal("5000.0"), new BigDecimal("10.0"));
        AuctionResultBatch batch = new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, new BigDecimal("240.0"), "test.csv", contracts);

        AuctionImportSession session = orchestrator.importAuctionResults(
            new ImportAuctionResults(TENANT, batch));

        assertEquals(AuctionImportStatus.IMPORTED, session.status());
        assertTrue(publishedEvents.stream()
            .filter(e -> e instanceof OperationalAlertRaised)
            .map(e -> (OperationalAlertRaised) e)
            .anyMatch(e -> e.severity() == AlertSeverity.WARNING
                        && e.category() == AlertCategory.DA_CLEARING_PRICES),
            "Expected WARNING-severity DA_CLEARING_PRICES alert for extreme price");
    }

    // ---------------------------------------------------------------------------
    // Null guard
    // ---------------------------------------------------------------------------

    @Test
    void nullCommand_throwsNpe() {
        assertThrows(NullPointerException.class, () -> orchestrator.importAuctionResults(null));
    }

    // ---------------------------------------------------------------------------
    // Factory / builder helpers
    // ---------------------------------------------------------------------------

    /** Build a batch with 96 individual spot contracts matching the market calendar. */
    private AuctionResultBatch batchOf96Contracts(BigDecimal exchangeReportedTotalMwh) {
        return new AuctionResultBatch(
            EXCHANGE, ZONE, DAY, exchangeReportedTotalMwh, "test.csv",
            build96Contracts(new BigDecimal("45.0"), new BigDecimal("1.0")));
    }

    /** Build 96 individual spot contracts, one per 15-min interval. */
    private List<AuctionResultContract> build96Contracts(BigDecimal price, BigDecimal volume) {
        List<AuctionResultContract> contracts = new ArrayList<>();
        Instant cursor = DAY_START;
        for (int i = 0; i < 96; i++) {
            Instant next = cursor.plusSeconds(900);
            contracts.add(new AuctionResultContract(
                "C-" + String.format("%04d", i), price, volume,
                cursor, next, null, BigDecimal.ONE, TradeDirection.BUY
            ));
            cursor = next;
        }
        return contracts;
    }

    private AuctionResultContract spotContract(String id, BigDecimal price, BigDecimal volume) {
        Instant start = DAY_START;
        Instant end   = start.plusSeconds(900);
        return new AuctionResultContract(id, price, volume, start, end,
            null, BigDecimal.ONE, TradeDirection.BUY);
    }

    private AuctionImportSession buildImportedSession() {
        return AuctionImportSession.builder()
            .sessionId(UUID.randomUUID())
            .tenantId(TENANT)
            .exchange(EXCHANGE)
            .biddingZone(ZONE)
            .deliveryDay(DAY)
            .importTimestamp(Instant.now().minusSeconds(3600))
            .status(AuctionImportStatus.IMPORTED)
            .exchangeReportedTotalMwh(new BigDecimal("240.0"))
            .intervalCount(96)
            .fileReference("test.csv")
            .createdAt(Instant.now().minusSeconds(3600))
            .completedAt(Instant.now().minusSeconds(3500))
            .build();
    }

    /** Create a minimal PositionLedgerEntry from a TradeCapture command. */
    private PositionLedgerEntry makeLedgerEntry(TradeCapture cmd) {
        DeliveryRange range = DeliveryRange.ofMonth(
            YearMonth.from(cmd.deliveryPeriod().start()), CET);
        return PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId(cmd.tenantId())
            .tradeId(cmd.tradeId())
            .tradeLegId(cmd.tradeLegId())
            .tradeVersion(cmd.tradeVersion())
            .deliveryRange(range)
            .deliveryStart(cmd.deliveryPeriod().start().toInstant())
            .deliveryEnd(cmd.deliveryPeriod().end().toInstant())
            .quantity(cmd.quantity())
            .direction(cmd.direction())
            .volumeUnit(cmd.volumeUnit())
            .priceExpressionId(cmd.priceExpressionId())
            .portfolioId(cmd.portfolioId())
            .deliveryPointId(cmd.deliveryPointId())
            .originType(cmd.originType())
            .multiplier(cmd.multiplier())
            .volumeSeriesKey(cmd.volumeSeriesKey())
            .cascadeGeneration(0)
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .status("ACTIVE")
            .amendmentReason("INITIAL")
            .build();
    }
}
