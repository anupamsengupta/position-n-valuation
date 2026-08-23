package com.power.posval.domain.service.da;

import com.power.posval.domain.command.NominationInterval;
import com.power.posval.domain.command.RecordNomination;
import com.power.posval.domain.event.NominationDeviationDetected;
import com.power.posval.domain.event.NominationRecorded;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.NominationDeviation;
import java.time.YearMonth;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.NominationRepository;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.service.OperationalAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultNominationService}.
 * Pattern #18, DA-VOL-03, FR-021, A-4.
 */
class NominationServiceTest {

    private static final String TENANT    = "TN_0042";
    private static final String BG_ID     = "BG-DE-001";
    private static final LocalDate DAY    = LocalDate.of(2026, 9, 16);
    private static final ZoneId CET       = ZoneId.of("Europe/Berlin");

    /** Delivery day start in UTC (CET midnight = 22:00Z the day before). */
    private static final Instant DAY_START = DAY.atStartOfDay(CET).toInstant();

    // 15-min slot on the delivery day
    private static final Instant SLOT_1_START = DAY_START;
    private static final Instant SLOT_1_END   = SLOT_1_START.plusSeconds(900);
    private static final Instant SLOT_2_START = SLOT_1_END;
    private static final Instant SLOT_2_END   = SLOT_2_START.plusSeconds(900);

    private final List<NominationRecord> savedNominations  = new ArrayList<>();
    private final List<Object>           publishedEvents   = new ArrayList<>();
    private final List<OperationalAlert> raisedAlerts      = new ArrayList<>();

    /** Stub in-memory nomination store; keyed by intervalStart for latest lookup. */
    private final Map<Instant, NominationRecord> latestByInterval = new HashMap<>();

    /** Positions returned by the ledger stub for the delivery day. */
    private final List<PositionLedgerEntry> stubPositions = new ArrayList<>();

    private DefaultNominationService service;

    @BeforeEach
    void setUp() {
        savedNominations.clear();
        publishedEvents.clear();
        raisedAlerts.clear();
        latestByInterval.clear();
        stubPositions.clear();

        NominationRepository nominationRepo = new NominationRepository() {
            @Override
            public void save(NominationRecord r) {
                savedNominations.add(r);
                latestByInterval.put(r.intervalStart(), r);
            }

            @Override
            public void saveAll(List<NominationRecord> records) {
                records.forEach(this::save);
            }

            @Override
            public List<NominationRecord> findByDeliveryDay(String tenantId,
                    String balancingGroupId, LocalDate deliveryDay) {
                return List.copyOf(savedNominations);
            }

            @Override
            public Optional<NominationRecord> findLatestByInterval(String tenantId,
                    String balancingGroupId, Instant intervalStart) {
                return Optional.ofNullable(latestByInterval.get(intervalStart));
            }
        };

        PositionLedgerRepository ledgerRepo = new StubPositionLedgerRepository(stubPositions);

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

        service = new DefaultNominationService(nominationRepo, ledgerRepo, alertService,
            publishedEvents::add);
    }

    // ---------------------------------------------------------------------------
    // recordNomination — happy path
    // ---------------------------------------------------------------------------

    @Test
    void recordNomination_savesTwoIntervalsAndPublishesEvent() {
        RecordNomination cmd = new RecordNomination(
            TENANT, BG_ID, DAY,
            List.of(
                new NominationInterval(SLOT_1_START, SLOT_1_END, new BigDecimal("30.0")),
                new NominationInterval(SLOT_2_START, SLOT_2_END, new BigDecimal("32.0"))
            ),
            Instant.now(), "scheduler"
        );

        List<NominationRecord> records = service.recordNomination(cmd);

        assertEquals(2, records.size());
        assertEquals(2, savedNominations.size());

        // NominationRecorded event must be published
        assertTrue(publishedEvents.stream().anyMatch(e -> e instanceof NominationRecorded),
            "Expected NominationRecorded event");
        NominationRecorded recorded = publishedEvents.stream()
            .filter(e -> e instanceof NominationRecorded)
            .map(e -> (NominationRecorded) e)
            .findFirst().orElseThrow();
        assertEquals(TENANT, recorded.tenantId());
        assertEquals(BG_ID, recorded.balancingGroupId());
        assertEquals(2, recorded.intervalCount());
    }

    @Test
    void recordNomination_firstVersionIs1() {
        RecordNomination cmd = new RecordNomination(
            TENANT, BG_ID, DAY,
            List.of(new NominationInterval(SLOT_1_START, SLOT_1_END, new BigDecimal("30.0"))),
            Instant.now(), "scheduler"
        );

        List<NominationRecord> records = service.recordNomination(cmd);
        assertEquals(1, records.get(0).nominationVersion());
    }

    @Test
    void recordNomination_subsequentVersionIncremented() {
        // First submission
        service.recordNomination(new RecordNomination(
            TENANT, BG_ID, DAY,
            List.of(new NominationInterval(SLOT_1_START, SLOT_1_END, new BigDecimal("30.0"))),
            Instant.now(), "scheduler"
        ));
        // Second submission for same interval
        List<NominationRecord> records = service.recordNomination(new RecordNomination(
            TENANT, BG_ID, DAY,
            List.of(new NominationInterval(SLOT_1_START, SLOT_1_END, new BigDecimal("31.0"))),
            Instant.now(), "scheduler"
        ));
        assertEquals(2, records.get(0).nominationVersion());
    }

    // ---------------------------------------------------------------------------
    // compareWithTraded — no deviation
    // ---------------------------------------------------------------------------

    @Test
    void compareWithTraded_noDeviation_returnsEmpty() {
        // Position with quantity = nominated
        stubPositions.add(makePosition(SLOT_1_START, SLOT_1_END, new BigDecimal("30.0")));

        // Record nomination matching the traded position
        service.recordNomination(new RecordNomination(
            TENANT, BG_ID, DAY,
            List.of(new NominationInterval(SLOT_1_START, SLOT_1_END, new BigDecimal("30.0"))),
            Instant.now(), "scheduler"
        ));

        // Clear events from the recordNomination call before checking deviations
        publishedEvents.clear();
        raisedAlerts.clear();

        List<NominationDeviation> deviations = service.compareWithTraded(TENANT, BG_ID, DAY);
        assertTrue(deviations.isEmpty(), "Expected no deviations when nominated == traded");
    }

    // ---------------------------------------------------------------------------
    // compareWithTraded — with deviation
    // ---------------------------------------------------------------------------

    @Test
    void compareWithTraded_deviation_returnsDeviationAndRaisesAlert() {
        // Position traded = 25 MW; nominated = 30 MW → deviation = 5 MW
        stubPositions.add(makePosition(SLOT_1_START, SLOT_1_END, new BigDecimal("25.0")));

        service.recordNomination(new RecordNomination(
            TENANT, BG_ID, DAY,
            List.of(new NominationInterval(SLOT_1_START, SLOT_1_END, new BigDecimal("30.0"))),
            Instant.now(), "scheduler"
        ));

        // Events emitted during recordNomination also include the deviation events
        long deviationEventCount = publishedEvents.stream()
            .filter(e -> e instanceof NominationDeviationDetected)
            .count();
        assertEquals(1, deviationEventCount);

        List<NominationDeviation> deviations = service.compareWithTraded(TENANT, BG_ID, DAY);
        assertEquals(1, deviations.size());
        NominationDeviation dev = deviations.get(0);
        assertEquals(0, dev.deviationMw().compareTo(new BigDecimal("5.0")));
        assertEquals(0, dev.tradedMw().compareTo(new BigDecimal("25.0")));
        assertEquals(0, dev.nominatedMw().compareTo(new BigDecimal("30.0")));
    }

    @Test
    void compareWithTraded_noNominations_returnsEmpty() {
        // No nominations recorded for the day
        List<NominationDeviation> deviations = service.compareWithTraded(TENANT, BG_ID, DAY);
        assertTrue(deviations.isEmpty());
    }

    // ---------------------------------------------------------------------------
    // Null guard
    // ---------------------------------------------------------------------------

    @Test
    void recordNomination_nullCommand_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.recordNomination(null));
    }

    @Test
    void compareWithTraded_nullTenant_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.compareWithTraded(null, BG_ID, DAY));
    }

    // ---------------------------------------------------------------------------
    // Stub helpers
    // ---------------------------------------------------------------------------

    private static PositionLedgerEntry makePosition(Instant start, Instant end, BigDecimal qty) {
        DeliveryRange range = DeliveryRange.ofMonth(
            YearMonth.from(start.atZone(CET)), CET);
        return PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId(TENANT)
            .tradeId("T-7788")
            .tradeLegId("T-7788/LEG1")
            .tradeVersion(1)
            .deliveryStart(start)
            .deliveryEnd(end)
            .deliveryRange(range)
            .quantity(qty)
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .portfolioId("DE_LU")
            .deliveryPointId("DE_LU")
            .originType("EXCHANGE_FILL")
            .multiplier(BigDecimal.ONE)
            .volumeSeriesKey(new SeriesKey("DA-PROFILE-T-7788"))
            .cascadeGeneration(0)
            .validFrom(Instant.now().minusSeconds(3600))
            .knownFrom(Instant.now().minusSeconds(3600))
            .status("ACTIVE")
            .amendmentReason("INITIAL")
            .build();
    }

    /** Minimal stub for PositionLedgerRepository. */
    private static class StubPositionLedgerRepository implements PositionLedgerRepository {
        private final List<PositionLedgerEntry> positions;

        StubPositionLedgerRepository(List<PositionLedgerEntry> positions) {
            this.positions = positions;
        }

        @Override public void save(PositionLedgerEntry e) {}
        @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
        @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) {
            return List.copyOf(positions);
        }
        @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
        @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
    }
}
