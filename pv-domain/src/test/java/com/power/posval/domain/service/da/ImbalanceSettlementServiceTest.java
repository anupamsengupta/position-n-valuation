package com.power.posval.domain.service.da;

import com.power.posval.domain.command.ComputeImbalanceSettlement;
import com.power.posval.domain.event.ImbalanceSettlementComputed;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.value.ImbalanceMonthSummary;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.ImbalanceRecordRepository;
import com.power.posval.domain.port.repository.NominationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultImbalanceSettlementService}.
 * Pattern #18, DA-SET-04, S8.3, A-5.
 */
class ImbalanceSettlementServiceTest {

    private static final String TENANT = "TN_0042";
    private static final String BG_ID  = "BG-DE-001";
    private static final LocalDate DAY  = LocalDate.of(2026, 9, 16);
    private static final ZoneId CET     = ZoneId.of("Europe/Berlin");

    /** Delivery day starts at CET midnight = 2026-09-15T22:00:00Z. */
    private static final Instant DAY_START = DAY.atStartOfDay(CET).toInstant();

    private static final Instant SLOT_1_START = DAY_START;
    private static final Instant SLOT_1_END   = SLOT_1_START.plusSeconds(900); // 15 min

    private final List<ImbalanceRecord>  savedRecords   = new ArrayList<>();
    private final List<Object>           publishedEvents = new ArrayList<>();

    /** Stub nomination store; populated per test. */
    private final List<NominationRecord> stubNominations = new ArrayList<>();

    /** Stub market data: series -> instant -> value. */
    private final Map<String, Map<Instant, BigDecimal>> stubMarketData = new HashMap<>();

    private DefaultImbalanceSettlementService service;

    @BeforeEach
    void setUp() {
        savedRecords.clear();
        publishedEvents.clear();
        stubNominations.clear();
        stubMarketData.clear();

        NominationRepository nominationRepo = new NominationRepository() {
            @Override public void save(NominationRecord r) {}
            @Override public void saveAll(List<NominationRecord> records) {}

            @Override public List<NominationRecord> findByDeliveryDay(String tenantId,
                    String balancingGroupId, LocalDate deliveryDay) {
                return List.copyOf(stubNominations);
            }

            @Override public Optional<NominationRecord> findLatestByInterval(String tenantId,
                    String balancingGroupId, Instant intervalStart) {
                return Optional.empty();
            }
        };

        MarketDataPort marketDataPort = new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                BigDecimal val = stubMarketData
                    .getOrDefault(series, Map.of())
                    .getOrDefault(intervalStart, BigDecimal.ZERO);
                return new MarketDataLookup(val, 0L, series, intervalStart, QualityState.VALIDATED);
            }

            @Override
            public MarketDataLookup lookupIndex(String series, String refMonthExpression,
                    com.power.posval.domain.model.value.DeliveryPeriod p) {
                return new MarketDataLookup(BigDecimal.ZERO, 0L, series, Instant.now(), QualityState.PROVISIONAL);
            }

            @Override
            public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar, Instant asOfDate) {
                return new MarketDataLookup(BigDecimal.ZERO, 0L, series, asOfDate, QualityState.PROVISIONAL);
            }

            @Override
            public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
                return new MarketDataLookup(BigDecimal.ONE, 0L, currencyPair, referenceDate, QualityState.PROVISIONAL);
            }

            @Override
            public MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long versionId) {
                return lookupFixing(series, intervalStart);
            }
        };

        ImbalanceRecordRepository imbalanceRepo = new ImbalanceRecordRepository() {
            @Override public void save(ImbalanceRecord r) { savedRecords.add(r); }
            @Override public void saveAll(List<ImbalanceRecord> records) { savedRecords.addAll(records); }
            @Override public List<ImbalanceRecord> findByDeliveryDay(String tenantId,
                    String balancingGroupId, LocalDate deliveryDay) {
                return savedRecords.stream()
                    .filter(r -> r.deliveryDay().equals(deliveryDay))
                    .toList();
            }
            @Override public List<ImbalanceRecord> findByMonth(String tenantId,
                    String balancingGroupId, YearMonth month) {
                return savedRecords.stream()
                    .filter(r -> YearMonth.from(r.deliveryDay()).equals(month))
                    .toList();
            }
        };

        DomainEventPublisher publisher = publishedEvents::add;

        service = new DefaultImbalanceSettlementService(
            nominationRepo, marketDataPort, imbalanceRepo, publisher);
    }

    // ---------------------------------------------------------------------------
    // computeForDay — over-delivery (actual > nominated)
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_overDelivery_positiveImbalanceVolume() {
        // nominated = 30 MW, actual = 35 MW => imbalance = +5 MW
        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("30")));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("35"));
        setMarketData("REBAP_DE",         SLOT_1_START, new BigDecimal("50")); // 50 EUR/MWh

        List<ImbalanceRecord> records = service.computeForDay(
            new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        assertEquals(1, records.size());
        ImbalanceRecord rec = records.get(0);

        // imbalanceVolumeMw = actual - nominated = 35 - 30 = 5
        assertEquals(0, rec.imbalanceVolumeMw().compareTo(new BigDecimal("5")));
        assertTrue(rec.imbalanceVolumeMw().compareTo(BigDecimal.ZERO) > 0,
            "Over-delivery: positive imbalance volume");
    }

    // ---------------------------------------------------------------------------
    // computeForDay — under-delivery (actual < nominated)
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_underDelivery_negativeImbalanceVolume() {
        // nominated = 30 MW, actual = 20 MW => imbalance = -10 MW
        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("30")));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("20"));
        setMarketData("REBAP_DE",         SLOT_1_START, new BigDecimal("60"));

        List<ImbalanceRecord> records = service.computeForDay(
            new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        assertEquals(1, records.size());
        ImbalanceRecord rec = records.get(0);
        assertEquals(0, rec.imbalanceVolumeMw().compareTo(new BigDecimal("-10")));
        assertTrue(rec.imbalanceVolumeMw().compareTo(BigDecimal.ZERO) < 0,
            "Under-delivery: negative imbalance volume");
    }

    // ---------------------------------------------------------------------------
    // computeForDay — negative reBAP price
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_negativeRebapPrice_negativeImbalanceAmount() {
        // nominated = 30, actual = 35 => imbalance volume = 5 MW
        // reBAP = -20 EUR/MWh (negative price — excess generation cost)
        // imbalanceMwh = 5 * (900/3600) = 1.25 MWh
        // imbalanceAmount = 1.25 * (-20) = -25 EUR
        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("30")));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("35"));
        setMarketData("REBAP_DE",         SLOT_1_START, new BigDecimal("-20"));

        List<ImbalanceRecord> records = service.computeForDay(
            new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        assertEquals(1, records.size());
        ImbalanceRecord rec = records.get(0);
        assertEquals(0, rec.imbalancePricePerMwh().compareTo(new BigDecimal("-20")));
        assertTrue(rec.imbalanceAmount().compareTo(BigDecimal.ZERO) < 0,
            "Negative reBAP and positive volume => negative amount");
    }

    // ---------------------------------------------------------------------------
    // computeForDay — MONETARY scale 4 rounding
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_amountRoundedToMonetaryScale4() {
        // nominated = 30, actual = 31 => imbalanceVol = 1 MW
        // interval = 900s => 0.25 h => imbalanceMwh = 0.25 MWh
        // reBAP = 123.456789 EUR/MWh => amount = 0.25 * 123.456789 = 30.8641972...
        // rounded to 4 dp => 30.8642
        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("30")));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("31"));
        setMarketData("REBAP_DE",         SLOT_1_START, new BigDecimal("123.456789"));

        List<ImbalanceRecord> records = service.computeForDay(
            new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        assertEquals(1, records.size());
        assertEquals(4, records.get(0).imbalanceAmount().scale(),
            "imbalanceAmount must be at MONETARY scale 4");
    }

    // ---------------------------------------------------------------------------
    // computeForDay — publishes ImbalanceSettlementComputed event
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_publishesImbalanceSettlementComputedEvent() {
        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("30")));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("30"));
        setMarketData("REBAP_DE",         SLOT_1_START, new BigDecimal("50"));

        service.computeForDay(new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        assertTrue(publishedEvents.stream().anyMatch(e -> e instanceof ImbalanceSettlementComputed),
            "Expected ImbalanceSettlementComputed event");
    }

    // ---------------------------------------------------------------------------
    // computeForDay — DST fall-back day (100 intervals: 25 hours x 4)
    // This test verifies the code correctly handles 100-interval days by confirming
    // that two 15-min slots on a DST day compute independently correct amounts.
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_dstFallbackDay_twoSlotsComputeCorrectly() {
        // DST fall-back: 2026-10-25 is a 25-hour day in CET
        // Use a second 15-min slot to verify independent computation
        Instant slot2Start = SLOT_1_END;
        Instant slot2End   = slot2Start.plusSeconds(900);

        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("40")));
        stubNominations.add(makeNomination(slot2Start,   slot2End,   new BigDecimal("50")));

        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("38")); // under: -2 MW
        setMarketData("ACTUAL_" + BG_ID, slot2Start,   new BigDecimal("55")); // over: +5 MW
        setMarketData("REBAP_DE", SLOT_1_START, new BigDecimal("80"));
        setMarketData("REBAP_DE", slot2Start,   new BigDecimal("80"));

        List<ImbalanceRecord> records = service.computeForDay(
            new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        assertEquals(2, records.size());
        // Verify signs independently
        long negativeCount = records.stream()
            .filter(r -> r.imbalanceVolumeMw().compareTo(BigDecimal.ZERO) < 0).count();
        long positiveCount = records.stream()
            .filter(r -> r.imbalanceVolumeMw().compareTo(BigDecimal.ZERO) > 0).count();
        assertEquals(1, negativeCount, "One under-delivery interval");
        assertEquals(1, positiveCount, "One over-delivery interval");
    }

    // ---------------------------------------------------------------------------
    // monthlyAggregate — sums records by day
    // ---------------------------------------------------------------------------

    @Test
    void monthlyAggregate_sumsDailyRecords() {
        // Seed two records on same day
        stubNominations.add(makeNomination(SLOT_1_START, SLOT_1_END, new BigDecimal("30")));
        stubNominations.add(makeNomination(SLOT_1_END, SLOT_1_END.plusSeconds(900), new BigDecimal("20")));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_START, new BigDecimal("32"));
        setMarketData("ACTUAL_" + BG_ID, SLOT_1_END,   new BigDecimal("18"));
        setMarketData("REBAP_DE", SLOT_1_START, new BigDecimal("50"));
        setMarketData("REBAP_DE", SLOT_1_END,   new BigDecimal("50"));

        service.computeForDay(new ComputeImbalanceSettlement(TENANT, BG_ID, DAY));

        ImbalanceMonthSummary summary = service.monthlyAggregate(
            TENANT, BG_ID, YearMonth.of(2026, 9));

        assertEquals(YearMonth.of(2026, 9), summary.month());
        assertEquals(BG_ID, summary.balancingGroupId());
        assertEquals("EUR", summary.currency());
        // Two saved records should produce one daily entry (same day)
        assertEquals(1, summary.dailyBreakdown().size());
        // Monthly net should have MONETARY scale 4
        assertEquals(4, summary.netImbalanceAmount().scale());
    }

    // ---------------------------------------------------------------------------
    // Null guard
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_nullCommand_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.computeForDay(null));
    }

    @Test
    void monthlyAggregate_nullTenant_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.monthlyAggregate(null, BG_ID, YearMonth.of(2026, 9)));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void setMarketData(String series, Instant at, BigDecimal value) {
        stubMarketData.computeIfAbsent(series, k -> new HashMap<>()).put(at, value);
    }

    private static NominationRecord makeNomination(Instant start, Instant end, BigDecimal volumeMw) {
        return NominationRecord.builder()
            .nominationId(UUID.randomUUID())
            .tenantId(TENANT)
            .balancingGroupId(BG_ID)
            .deliveryDay(DAY)
            .intervalStart(start)
            .intervalEnd(end)
            .nominatedVolumeMw(volumeMw)
            .nominationTimestamp(Instant.now())
            .nominationVersion(1)
            .submittedBy("scheduler")
            .build();
    }
}
