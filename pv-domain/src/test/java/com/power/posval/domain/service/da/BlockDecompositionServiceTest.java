package com.power.posval.domain.service.da;

import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.MarketCalendarPort;
import com.power.posval.domain.port.repository.BlockDefinitionRepository;
import com.power.posval.domain.port.repository.HolidayCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultBlockDecompositionService}.
 * Pattern #18, DA-VOL-02.
 */
class BlockDecompositionServiceTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    /** A standard (non-DST) delivery day: 2026-09-16. */
    private static final LocalDate STANDARD_DAY   = LocalDate.of(2026, 9, 16);
    /** DST spring-forward: 2026-03-29 (clocks jump from 02:00 to 03:00 CET). */
    private static final LocalDate DST_SPRING_DAY = LocalDate.of(2026, 3, 29);
    /** DST fall-back: 2026-10-25 (clocks go back from 03:00 to 02:00 CET). */
    private static final LocalDate DST_FALL_DAY   = LocalDate.of(2026, 10, 25);

    private boolean isHolidayStubResult = false;

    private DefaultBlockDecompositionService service;

    @BeforeEach
    void setUp() {
        MarketCalendarPort calendarPort = new DstAwareMarketCalendarPort();
        HolidayCalendarRepository holidayRepo = new HolidayCalendarRepository() {
            @Override public boolean isHoliday(String zone, LocalDate date) { return isHolidayStubResult; }
            @Override public List<com.power.posval.domain.model.HolidayCalendar> findByZoneAndYear(String zone, int year) { return List.of(); }
        };
        BlockDefinitionRepository blockDefRepo = new BlockDefinitionRepository() {
            @Override public Optional<BlockDefinition> findEffective(String exchange,
                    BlockType blockType, String biddingZone, LocalDate deliveryDate) {
                return Optional.empty();
            }
            @Override public List<BlockDefinition> findAllEffective(String exchange,
                    LocalDate deliveryDate) {
                return List.of();
            }
        };
        service = new DefaultBlockDecompositionService(calendarPort, holidayRepo, blockDefRepo);
    }

    // ---------------------------------------------------------------------------
    // Non-block (spot) contract passes through as-is
    // ---------------------------------------------------------------------------

    @Test
    void nonBlockContract_passedThroughAsIs() {
        AuctionResultContract spot = spotContract(STANDARD_DAY, new BigDecimal("30.0"));
        List<AuctionResultContract> result = service.decompose(spot, null, STANDARD_DAY);
        assertEquals(1, result.size());
        assertSame(spot, result.get(0));
    }

    // ---------------------------------------------------------------------------
    // BASELOAD decomposition: all 96 intervals on a standard day
    // ---------------------------------------------------------------------------

    @Test
    void baseload_standardDay_96intervals() {
        AuctionResultContract blockOrder = blockContract("BASELOAD", new BigDecimal("100.0"),
            BigDecimal.ONE, STANDARD_DAY);
        BlockDefinition baseloadDef = baseloadDefinition(STANDARD_DAY);

        List<AuctionResultContract> expanded = service.decompose(blockOrder, baseloadDef, STANDARD_DAY);

        assertEquals(96, expanded.size(), "Standard day baseload: 96 quarter-hourly intervals");
        // All expanded intervals have null blockType (they are now individual contracts)
        assertTrue(expanded.stream().allMatch(c -> c.blockType() == null));
    }

    // ---------------------------------------------------------------------------
    // PEAK decomposition: hours 08:00–20:00 CET = 48 intervals
    // ---------------------------------------------------------------------------

    @Test
    void peak_standardDay_48intervals() {
        AuctionResultContract blockOrder = blockContract("PEAK", new BigDecimal("80.0"),
            BigDecimal.ONE, STANDARD_DAY);
        BlockDefinition peakDef = peakDefinition(STANDARD_DAY, "DE");

        isHolidayStubResult = false; // standard business day, not a holiday
        List<AuctionResultContract> expanded = service.decompose(blockOrder, peakDef, STANDARD_DAY);

        // Peak = 12 hours x 4 intervals/hour = 48
        assertEquals(48, expanded.size(), "Standard day peak: 48 quarter-hourly intervals");
    }

    // ---------------------------------------------------------------------------
    // OFF_PEAK decomposition: morning off-peak window 00:00–08:00 = 32 intervals
    // ---------------------------------------------------------------------------

    @Test
    void offPeak_standardDay_32intervalsInMorningWindow() {
        AuctionResultContract blockOrder = blockContract("OFF_PEAK", new BigDecimal("50.0"),
            BigDecimal.ONE, STANDARD_DAY);
        BlockDefinition offPeakDef = offPeakDefinition(STANDARD_DAY);

        List<AuctionResultContract> expanded = service.decompose(blockOrder, offPeakDef, STANDARD_DAY);

        // Off-peak morning window: 00:00 to 08:00 = 8 hours x 4 = 32 intervals
        assertEquals(32, expanded.size(), "Standard day off-peak morning window: 32 intervals");
    }

    // ---------------------------------------------------------------------------
    // DST spring-forward: 2026-03-29 → 92 intervals for a full baseload
    // ---------------------------------------------------------------------------

    @Test
    void baseload_dstSpringForwardDay_92intervals() {
        AuctionResultContract blockOrder = blockContract("BASELOAD", new BigDecimal("100.0"),
            BigDecimal.ONE, DST_SPRING_DAY);
        BlockDefinition baseloadDef = baseloadDefinition(DST_SPRING_DAY);

        List<AuctionResultContract> expanded = service.decompose(blockOrder, baseloadDef, DST_SPRING_DAY);

        assertEquals(92, expanded.size(), "DST spring-forward: 23 hours x 4 = 92 intervals");
    }

    // ---------------------------------------------------------------------------
    // DST fall-back: 2026-10-25 → 100 intervals for a full baseload
    // ---------------------------------------------------------------------------

    @Test
    void baseload_dstFallBackDay_100intervals() {
        AuctionResultContract blockOrder = blockContract("BASELOAD", new BigDecimal("100.0"),
            BigDecimal.ONE, DST_FALL_DAY);
        BlockDefinition baseloadDef = baseloadDefinition(DST_FALL_DAY);

        List<AuctionResultContract> expanded = service.decompose(blockOrder, baseloadDef, DST_FALL_DAY);

        assertEquals(100, expanded.size(), "DST fall-back: 25 hours x 4 = 100 intervals");
    }

    // ---------------------------------------------------------------------------
    // Holiday exclusion: PEAK product on a public holiday → empty list
    // ---------------------------------------------------------------------------

    @Test
    void peak_onHoliday_returnsEmptyList() {
        AuctionResultContract blockOrder = blockContract("PEAK", new BigDecimal("80.0"),
            BigDecimal.ONE, STANDARD_DAY);
        BlockDefinition peakDef = peakDefinition(STANDARD_DAY, "DE");

        isHolidayStubResult = true; // the day is a public holiday

        List<AuctionResultContract> expanded = service.decompose(blockOrder, peakDef, STANDARD_DAY);

        assertTrue(expanded.isEmpty(), "PEAK block on a public holiday should expand to empty list");
    }

    // ---------------------------------------------------------------------------
    // Execution ratio applied: 0.5 → half volume on each expanded interval
    // ---------------------------------------------------------------------------

    @Test
    void executionRatio_halfExecution_halvesVolume() {
        BigDecimal originalVolume = new BigDecimal("100.0");
        BigDecimal executionRatio = new BigDecimal("0.5");
        AuctionResultContract blockOrder = blockContract("BASELOAD", originalVolume,
            executionRatio, STANDARD_DAY);
        BlockDefinition baseloadDef = baseloadDefinition(STANDARD_DAY);

        List<AuctionResultContract> expanded = service.decompose(blockOrder, baseloadDef, STANDARD_DAY);

        BigDecimal expectedVolume = originalVolume.multiply(executionRatio); // 50.0
        for (AuctionResultContract c : expanded) {
            assertEquals(0, c.volumeMw().compareTo(expectedVolume),
                "Each expanded interval should have volume = 100 * 0.5 = 50 MW");
        }
    }

    @Test
    void executionRatio_fullExecution_preservesVolume() {
        AuctionResultContract blockOrder = blockContract("BASELOAD", new BigDecimal("60.0"),
            BigDecimal.ONE, STANDARD_DAY);
        BlockDefinition baseloadDef = baseloadDefinition(STANDARD_DAY);

        List<AuctionResultContract> expanded = service.decompose(blockOrder, baseloadDef, STANDARD_DAY);

        for (AuctionResultContract c : expanded) {
            assertEquals(0, c.volumeMw().compareTo(new BigDecimal("60.0")));
        }
    }

    // ---------------------------------------------------------------------------
    // Null guards
    // ---------------------------------------------------------------------------

    @Test
    void nullContract_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.decompose(null, null, STANDARD_DAY));
    }

    @Test
    void blockContractWithNullBlockDef_throwsNpe() {
        AuctionResultContract blockOrder = blockContract("BASELOAD", new BigDecimal("100.0"),
            BigDecimal.ONE, STANDARD_DAY);
        // blockDef is null but contract has a blockType — should throw
        assertThrows(NullPointerException.class,
            () -> service.decompose(blockOrder, null, STANDARD_DAY));
    }

    // ---------------------------------------------------------------------------
    // Factory helpers
    // ---------------------------------------------------------------------------

    private static AuctionResultContract spotContract(LocalDate day, BigDecimal volume) {
        Instant start = day.atStartOfDay(CET).toInstant();
        Instant end   = start.plusSeconds(900);
        return new AuctionResultContract("SPOT-001", new BigDecimal("45.00"), volume,
            start, end, null, BigDecimal.ONE, TradeDirection.BUY);
    }

    private static AuctionResultContract blockContract(String blockType, BigDecimal volume,
            BigDecimal executionRatio, LocalDate day) {
        Instant start = day.atStartOfDay(CET).toInstant();
        Instant end   = day.plusDays(1).atStartOfDay(CET).toInstant();
        return new AuctionResultContract("BLOCK-001", new BigDecimal("44.00"), volume,
            start, end, blockType, executionRatio, TradeDirection.BUY);
    }

    /**
     * BASELOAD definition: 00:00 start, 23:45 end covers all 96 quarter-hourly intervals
     * (last 15-min slot starts at 23:45, so endHour 23:45 is inclusive via the < check).
     * Using 23:59 to safely capture the 23:45 slot.
     */
    private static BlockDefinition baseloadDefinition(LocalDate effectiveFrom) {
        return BlockDefinition.builder()
            .blockId(UUID.randomUUID())
            .exchange("EPEX_SPOT")
            .blockType(BlockType.BASELOAD)
            .biddingZone("DE_LU")
            .startHour(LocalTime.of(0, 0))
            .endHour(LocalTime.of(23, 59))   // covers all slots (last starts at 23:45)
            .applicableDays("MON-SUN")
            .effectiveFrom(effectiveFrom.minusDays(1))
            .build();
    }

    /** PEAK definition: 08:00–20:00 CET with holiday calendar reference. */
    private static BlockDefinition peakDefinition(LocalDate effectiveFrom, String holidayZone) {
        return BlockDefinition.builder()
            .blockId(UUID.randomUUID())
            .exchange("EPEX_SPOT")
            .blockType(BlockType.PEAK)
            .biddingZone("DE_LU")
            .startHour(LocalTime.of(8, 0))
            .endHour(LocalTime.of(20, 0))
            .applicableDays("MON-FRI")
            .holidayCalendarRef(holidayZone)
            .effectiveFrom(effectiveFrom.minusDays(1))
            .build();
    }

    /** OFF_PEAK definition: 00:00–08:00 CET. */
    private static BlockDefinition offPeakDefinition(LocalDate effectiveFrom) {
        return BlockDefinition.builder()
            .blockId(UUID.randomUUID())
            .exchange("EPEX_SPOT")
            .blockType(BlockType.OFF_PEAK)
            .biddingZone("DE_LU")
            .startHour(LocalTime.of(0, 0))
            .endHour(LocalTime.of(8, 0))
            .applicableDays("MON-SUN")
            .effectiveFrom(effectiveFrom.minusDays(1))
            .build();
    }

    // ---------------------------------------------------------------------------
    // DST-correct market calendar implementation (no framework dependency)
    // ---------------------------------------------------------------------------

    /**
     * Generates delivery intervals using pure java.time DST-correct arithmetic.
     * This mirrors what the production StubMarketCalendarAdapter does.
     */
    private static final class DstAwareMarketCalendarPort implements MarketCalendarPort {

        @Override
        public List<DeliveryPeriod> intervalsForDay(String biddingZone, LocalDate deliveryDay,
                TimeGranularity granularity) {
            ZonedDateTime dayStart = deliveryDay.atStartOfDay(CET);
            ZonedDateTime dayEnd   = deliveryDay.plusDays(1).atStartOfDay(CET);
            Duration step = granularity == TimeGranularity.MIN_15
                ? Duration.ofMinutes(15) : Duration.ofHours(1);

            List<DeliveryPeriod> intervals = new ArrayList<>();
            ZonedDateTime cursor = dayStart;
            while (cursor.isBefore(dayEnd)) {
                ZonedDateTime next = cursor.plus(step);
                intervals.add(new DeliveryPeriod(cursor, next, CET));
                cursor = next;
            }
            return List.copyOf(intervals);
        }

        @Override
        public int intervalCount(String biddingZone, LocalDate deliveryDay,
                TimeGranularity granularity) {
            return intervalsForDay(biddingZone, deliveryDay, granularity).size();
        }

        @Override
        public boolean isPeakInterval(String biddingZone, DeliveryPeriod interval) {
            int hour = interval.start().getHour();
            return hour >= 8 && hour < 20;
        }

        @Override
        public DeliveryPeriod deliveryDayBoundaries(String biddingZone, LocalDate deliveryDay) {
            ZonedDateTime start = deliveryDay.atStartOfDay(CET);
            ZonedDateTime end   = deliveryDay.plusDays(1).atStartOfDay(CET);
            return new DeliveryPeriod(start, end, CET);
        }
    }
}
