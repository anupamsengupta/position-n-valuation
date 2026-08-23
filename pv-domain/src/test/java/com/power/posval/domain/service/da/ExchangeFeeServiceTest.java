package com.power.posval.domain.service.da;

import com.power.posval.domain.command.ComputeExchangeFees;
import com.power.posval.domain.event.ExchangeFeesComputed;
import com.power.posval.domain.model.ExchangeFeeSchedule;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.ExchangeFeeResult;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.ExchangeFeeScheduleRepository;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Unit tests for {@link DefaultExchangeFeeService}.
 * Pattern #18, DA-SET-03, S8.4.
 */
class ExchangeFeeServiceTest {

    private static final String TENANT   = "TN_0042";
    private static final String EXCHANGE = "EPEX_SPOT";
    private static final LocalDate DAY   = LocalDate.of(2026, 9, 16);
    private static final ZoneId CET      = ZoneId.of("Europe/Berlin");

    /** Delivery day start (CET midnight = 2026-09-15T22:00:00Z). */
    private static final Instant DAY_START = DAY.atStartOfDay(CET).toInstant();

    private final List<Object>             publishedEvents = new ArrayList<>();
    private final List<PositionLedgerEntry> stubPositions  = new ArrayList<>();
    private final Map<String, ExchangeFeeSchedule> tradingSchedule  = new HashMap<>();
    private final Map<String, ExchangeFeeSchedule> clearingSchedule = new HashMap<>();

    private DefaultExchangeFeeService service;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        stubPositions.clear();
        tradingSchedule.clear();
        clearingSchedule.clear();

        PositionLedgerRepository ledgerRepo = new StubPositionLedgerRepository(stubPositions);

        ExchangeFeeScheduleRepository feeScheduleRepo = new ExchangeFeeScheduleRepository() {
            @Override
            public Optional<ExchangeFeeSchedule> findEffective(String tenantId, String exchange,
                    String feeType, LocalDate deliveryDate) {
                if ("TRADING".equals(feeType)) return Optional.ofNullable(tradingSchedule.get(tenantId));
                if ("CLEARING".equals(feeType)) return Optional.ofNullable(clearingSchedule.get(tenantId));
                return Optional.empty();
            }

            @Override
            public Optional<ExchangeFeeSchedule> findEffective(String tenantId, String exchange,
                    String feeType, String memberTier, LocalDate deliveryDate) {
                return findEffective(tenantId, exchange, feeType, deliveryDate);
            }

            @Override
            public void save(ExchangeFeeSchedule schedule) {}
        };

        DomainEventPublisher publisher = publishedEvents::add;
        NumericPrecision precision = new StubNumericPrecision();
        service = new DefaultExchangeFeeService(ledgerRepo, feeScheduleRepo, publisher, precision);
    }

    // ---------------------------------------------------------------------------
    // Gross volume = abs(qty) for each direction (BUY + SELL both count)
    // ---------------------------------------------------------------------------

    @Test
    void grossVolume_buyAndSellBothContribute() {
        // BUY 40 MW and SELL 30 MW, each over a 15-min interval (0.25 h)
        // grossMwh = (40 * 0.25) + (30 * 0.25) = 10 + 7.5 = 17.5 MWh
        Instant s1 = DAY_START;
        Instant e1 = s1.plusSeconds(900);
        Instant s2 = e1;
        Instant e2 = s2.plusSeconds(900);

        stubPositions.add(makePosition(s1, e1, new BigDecimal("40"), TradeDirection.BUY));
        stubPositions.add(makePosition(s2, e2, new BigDecimal("-30"), TradeDirection.SELL));

        setFeeSchedule("TRADING", new BigDecimal("0.10000000"));
        setFeeSchedule("CLEARING", new BigDecimal("0.05000000"));

        ExchangeFeeResult result = service.computeForDay(
            new ComputeExchangeFees(TENANT, EXCHANGE, DAY));

        // grossVolumeMwh = abs(40) * 0.25 + abs(-30) * 0.25 = 17.5
        assertEquals(0, result.grossVolumeMwh().compareTo(new BigDecimal("17.5")),
            "Gross volume should sum absolute quantities for both BUY and SELL");
        assertEquals(2, result.items().size(), "One line item per fee type");
    }

    // ---------------------------------------------------------------------------
    // Effective-date fee schedule lookup per fee type
    // ---------------------------------------------------------------------------

    @Test
    void feeScheduleLookup_tradingAndClearingLineItems() {
        Instant s = DAY_START;
        Instant e = s.plusSeconds(900);
        stubPositions.add(makePosition(s, e, new BigDecimal("100"), TradeDirection.BUY));

        setFeeSchedule("TRADING",  new BigDecimal("0.06000000"));
        setFeeSchedule("CLEARING", new BigDecimal("0.04000000"));

        ExchangeFeeResult result = service.computeForDay(
            new ComputeExchangeFees(TENANT, EXCHANGE, DAY));

        assertEquals(2, result.items().size());
        assertTrue(result.items().stream().anyMatch(i -> "TRADING".equals(i.feeType())));
        assertTrue(result.items().stream().anyMatch(i -> "CLEARING".equals(i.feeType())));
    }

    // ---------------------------------------------------------------------------
    // No schedule configured for CLEARING → only TRADING line item produced
    // ---------------------------------------------------------------------------

    @Test
    void missingClearingSchedule_onlyTradingLineItem() {
        Instant s = DAY_START;
        Instant e = s.plusSeconds(900);
        stubPositions.add(makePosition(s, e, new BigDecimal("100"), TradeDirection.BUY));

        // Only TRADING schedule configured
        setFeeSchedule("TRADING", new BigDecimal("0.06000000"));
        // No CLEARING schedule

        ExchangeFeeResult result = service.computeForDay(
            new ComputeExchangeFees(TENANT, EXCHANGE, DAY));

        assertEquals(1, result.items().size());
        assertEquals("TRADING", result.items().get(0).feeType());
    }

    // ---------------------------------------------------------------------------
    // MONETARY scale 4 rounding
    // ---------------------------------------------------------------------------

    @Test
    void feeAmount_roundedToMonetaryScale4() {
        // grossMwh = 100 MW * 0.25 h = 25 MWh
        // tradingRate = 0.12345678 EUR/MWh => amount = 25 * 0.12345678 = 3.0864195
        // rounded to scale 4 => 3.0864
        Instant s = DAY_START;
        Instant e = s.plusSeconds(900);
        stubPositions.add(makePosition(s, e, new BigDecimal("100"), TradeDirection.BUY));

        setFeeSchedule("TRADING", new BigDecimal("0.12345678"));

        ExchangeFeeResult result = service.computeForDay(
            new ComputeExchangeFees(TENANT, EXCHANGE, DAY));

        assertEquals(4, result.totalFeeAmount().scale(),
            "Total fee amount must be at MONETARY scale 4");
    }

    // ---------------------------------------------------------------------------
    // No positions → grossVolumeMwh = 0, line items computed at zero
    // ---------------------------------------------------------------------------

    @Test
    void noPositions_grossVolumeIsZero() {
        setFeeSchedule("TRADING",  new BigDecimal("0.06000000"));
        setFeeSchedule("CLEARING", new BigDecimal("0.04000000"));

        ExchangeFeeResult result = service.computeForDay(
            new ComputeExchangeFees(TENANT, EXCHANGE, DAY));

        assertEquals(0, result.grossVolumeMwh().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.totalFeeAmount().compareTo(BigDecimal.ZERO));
    }

    // ---------------------------------------------------------------------------
    // ExchangeFeesComputed event published
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_publishesExchangeFeesComputedEvent() {
        Instant s = DAY_START;
        Instant e = s.plusSeconds(900);
        stubPositions.add(makePosition(s, e, new BigDecimal("50"), TradeDirection.BUY));
        setFeeSchedule("TRADING", new BigDecimal("0.10000000"));

        service.computeForDay(new ComputeExchangeFees(TENANT, EXCHANGE, DAY));

        assertTrue(publishedEvents.stream().anyMatch(ev -> ev instanceof ExchangeFeesComputed),
            "Expected ExchangeFeesComputed event");
        ExchangeFeesComputed evt = publishedEvents.stream()
            .filter(ev -> ev instanceof ExchangeFeesComputed)
            .map(ev -> (ExchangeFeesComputed) ev)
            .findFirst().orElseThrow();
        assertEquals(TENANT, evt.tenantId());
        assertEquals(EXCHANGE, evt.exchange());
        assertEquals(DAY, evt.deliveryDay());
        assertEquals("EUR", evt.currency());
    }

    // ---------------------------------------------------------------------------
    // Null guard
    // ---------------------------------------------------------------------------

    @Test
    void computeForDay_nullCommand_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.computeForDay(null));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void setFeeSchedule(String feeType, BigDecimal rate) {
        ExchangeFeeSchedule schedule = ExchangeFeeSchedule.builder()
            .scheduleId(UUID.randomUUID())
            .tenantId(TENANT)
            .exchange(EXCHANGE)
            .feeType(feeType)
            .ratePerMwh(rate)
            .effectiveFrom(DAY.minusDays(30))
            .build();
        if ("TRADING".equals(feeType)) tradingSchedule.put(TENANT, schedule);
        else clearingSchedule.put(TENANT, schedule);
    }

    private static PositionLedgerEntry makePosition(Instant start, Instant end,
            BigDecimal qty, TradeDirection direction) {
        ZoneId cet = ZoneId.of("Europe/Berlin");
        DeliveryRange range = DeliveryRange.ofMonth(
            YearMonth.from(start.atZone(cet)), cet);
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
            .direction(direction)
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

    /** Stub NumericPrecision with standard scales. */
    private static class StubNumericPrecision implements NumericPrecision {
        @Override
        public int scale(Domain domain) {
            return switch (domain) {
                case MONETARY     -> 4;
                case PRICE        -> 8;
                case ENERGY       -> 8;
                case VOLUME       -> 4;
                case MULTIPLIER   -> 8;
                case INTERMEDIATE -> 10;
            };
        }

        @Override
        public int precision(Domain domain) { return 20; }

        @Override
        public RoundingMode roundingMode() { return RoundingMode.HALF_UP; }
    }

    /** Minimal stub PositionLedgerRepository. */
    private static class StubPositionLedgerRepository implements PositionLedgerRepository {
        private final List<PositionLedgerEntry> positions;

        StubPositionLedgerRepository(List<PositionLedgerEntry> positions) {
            this.positions = positions;
        }

        @Override public void save(PositionLedgerEntry e) {}
        @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
        @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
        @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return List.copyOf(positions); }
        @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
        @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
    }
}
