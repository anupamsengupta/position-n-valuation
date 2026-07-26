package com.power.posval.domain.benchmark;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.expression.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.service.*;
import com.power.posval.domain.service.stub.JsonMarketDataPort;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: end-to-end settlement materialization for a 5-year wind PPA.
 *
 * Simulates a real-world scenario:
 *   - 5-year delivery (2025-01-01 to 2029-12-31) = 60 monthly blocks
 *   - 15-minute granularity = ~175,200 intervals total
 *   - EXPR-4: CPI-escalated collar with negative-price protection (3-level tree)
 *   - All data pre-loaded in memory (volume intervals, market data, expressions)
 *
 * What is benchmarked:
 *   - Position loop: iterate 60 monthly PositionLedgerEntry records
 *   - Per entry: resolveVolume → evaluatePrice (tree walk) → writeResult
 *   - Price evaluation: ConditionalGate → Clamp → Escalate → Divide (6 leaves)
 *
 * What is NOT benchmarked (pre-loaded in @Setup):
 *   - JSON parsing, file I/O, volume series construction, interval generation
 *
 * Run with: mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=FiveYear
 * Smoke test via: BenchmarkSmokeTest.fiveYearSettlementBenchmark_executes()
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class FiveYearSettlementBenchmark {

    // Pre-built components
    private SettlementMaterializationJob settlementJob;
    private List<PositionLedgerEntry> monthlyPositions;
    private Map<YearMonth, DeliveryRange> monthRanges;

    // Counters for verification
    private int cellCount;

    @Setup(Level.Trial)
    public void setup() {
        var tz = ZoneId.of("Europe/Berlin");

        // --- Build 5-year volume series: 15-min intervals ---
        var allIntervals = new ArrayList<VolumeInterval>();
        ZonedDateTime start = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, tz);
        ZonedDateTime end = ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, tz);
        ZonedDateTime cursor = start;
        int seq = 0;
        var random = new Random(42);

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(15);
            // Simulate wind park output: 20-70 MW with hourly variation
            double baseMw = 45.0 + 20.0 * Math.sin(cursor.getHour() * Math.PI / 12.0);
            double mw = Math.max(0, baseMw + random.nextGaussian() * 8.0);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, java.math.RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(new BigDecimal("0.25"))
                .setScale(3, java.math.RoundingMode.HALF_UP);

            allIntervals.add(new DefaultVolumeInterval(
                new UUID(0L, ++seq),
                cursor.toInstant(), next.toInstant(),
                volume, energy, 1, null));

            cursor = next;
        }

        SeriesKey seriesKey = new SeriesKey("VS-BENCH-5YR");

        // --- Build monthly PositionLedgerEntry records (60 months) ---
        UUID priceExprId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        monthlyPositions = new ArrayList<>();
        monthRanges = new LinkedHashMap<>();

        YearMonth ym = YearMonth.of(2025, 1);
        YearMonth endYm = YearMonth.of(2030, 1);
        while (ym.isBefore(endYm)) {
            DeliveryRange range = DeliveryRange.ofMonth(ym, tz);
            monthRanges.put(ym, range);

            monthlyPositions.add(PositionLedgerEntry.builder()
                .id(new UUID(1L, ym.atDay(1).toEpochDay()))
                .tenantId("TN_BENCH")
                .tradeId("T-BENCH-5YR")
                .tradeLegId("LEG-1")
                .tradeVersion(1)
                .deliveryRange(range)
                .quantity(new BigDecimal("80.0"))
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .priceExpressionId(priceExprId)
                .volumeSeriesKey(seriesKey)
                .validFrom(Instant.parse("2024-12-01T00:00:00Z"))
                .knownFrom(Instant.parse("2024-12-01T00:00:00Z"))
                .build());

            ym = ym.plusMonths(1);
        }

        // --- Build volume series repo with all intervals ---
        VolumeSeries series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(seriesKey)
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(new DeliveryPeriod(start, end, tz))
            .qualityState(QualityState.EFFECTIVE)
            .transactionTime(Instant.now())
            .intervals(allIntervals)
            .build();

        VolumeSeriesRepository seriesRepo = new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) {}
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String t, String sk) {
                return sk.equals(seriesKey.value()) ? Optional.of(series) : Optional.empty();
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String t, int v) { return false; }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };

        // --- In-memory market data (fast HashMap lookups) ---
        //MarketDataPort marketData = new InMemoryMarketDataPort();
        MarketDataPort marketData = new JsonMarketDataPort();


        // --- EXPR-4: CPI-escalated collar with neg-price gate ---
        PriceExpression expr4 = new ConditionalGate(
            new MarketDataLeaf("EPEX_DA15_GATE", "EPEX_DA15", "EPEX_DA15_SETTLE", 0, "SINGLE_INTERVAL"),
            "< 0",
            new ConstantLeaf("ZERO_OVERRIDE", BigDecimal.ZERO, "EUR/MWh"),
            new Clamp(
                new ConstantLeaf("FLOOR_38", new BigDecimal("38.00"), "EUR/MWh"),
                new ConstantLeaf("CAP_110", new BigDecimal("110.00"), "EUR/MWh"),
                new Escalate(
                    new ConstantLeaf("BASE_PRICE_72", new BigDecimal("72.00"), "EUR/MWh"),
                    new Divide(
                        new IndexLeaf("HICP_DE_CURRENT", "HICP-DE", "deliveryYear-1:November"),
                        new ConstantLeaf("HICP_DE_BASE_2023", new BigDecimal("108.70"), "INDEX")))));

        PriceExpressionRepository exprRepo = id ->
            id.equals(priceExprId) ? Optional.of(expr4) : Optional.empty();

        // --- Wire the job ---
        var priceEvaluator = new DefaultPriceEvaluator(new DefaultNumericPrecision());
        var volumeResolver = new ProfileResolver(seriesRepo, new DefaultNumericPrecision());

        cellCount = 0;
        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(SettlementCell cell) { cellCount++; }
            @Override public List<SettlementCell> findByPosition(String t, UUID p,
                                                                  Instant s, Instant e) {
                return List.of();
            }
        };
        DomainEventPublisher eventPublisher = e -> {};

        settlementJob = new SettlementMaterializationJob(
            volumeResolver, priceEvaluator, marketData, exprRepo,
            cellRepo, eventPublisher, new DefaultNumericPrecision());
    }

    /**
     * Benchmark: materialize all 60 monthly positions of a 5-year trade.
     * Each position resolves volume intervals and evaluates the 3-level
     * price expression tree per interval.
     */
    @Benchmark
    public int materializeFiveYearTrade(Blackhole bh) {
        cellCount = 0;
        for (PositionLedgerEntry position : monthlyPositions) {
            DeliveryRange range = monthRanges.get(
                YearMonth.from(position.deliveryRange().startMonth()));
            settlementJob.execute(position, range);
        }
        if (bh != null) bh.consume(cellCount);
        return cellCount;
    }

    /**
     * Benchmark: materialize a single month (for per-month throughput analysis).
     */
    @Benchmark
    public int materializeSingleMonth(Blackhole bh) {
        cellCount = 0;
        PositionLedgerEntry position = monthlyPositions.get(0);
        DeliveryRange range = monthRanges.get(position.deliveryRange().startMonth());
        settlementJob.execute(position, range);
        if (bh != null) bh.consume(cellCount);
        return cellCount;
    }

    /**
     * Fast in-memory market data — HashMap lookups, no JSON parsing.
     * Returns realistic but deterministic values for benchmarking.
     */
    private static class InMemoryMarketDataPort implements MarketDataPort {
        // Pre-computed hourly price pattern (24 values, EUR/MWh)
        private static final double[] HOURLY_PRICES = {
            28, 24, 22, 21, 23, 32, 52, 68,
            78, 82, 75, 68, 58, 52, 55, 62,
            72, 88, 95, 85, 72, 58, 42, 35
        };

        @Override
        public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
            int hour = intervalStart.atZone(ZoneId.of("Europe/Berlin")).getHour();
            double price = HOURLY_PRICES[hour] + (intervalStart.getEpochSecond() % 7) - 3;
            return new MarketDataLookup(
                BigDecimal.valueOf(price).setScale(2, java.math.RoundingMode.HALF_UP),
                1L, series, intervalStart, null);
        }

        @Override
        public MarketDataLookup lookupIndex(String series, String refMonthExpr, DeliveryPeriod dp) {
            return new MarketDataLookup(
                new BigDecimal("112.30"), 1L, series, dp.start().toInstant(), null);
        }

        @Override
        public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar, Instant asOf) {
            return new MarketDataLookup(
                new BigDecimal("74.20"), 1L, series, asOf, null);
        }

        @Override
        public MarketDataLookup lookupFxRate(String pair, Instant refDate) {
            return new MarketDataLookup(BigDecimal.ONE, 1L, pair, refDate, null);
        }

        @Override
        public MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long versionId) {
            return lookupFixing(series, intervalStart);
        }
    }
}
