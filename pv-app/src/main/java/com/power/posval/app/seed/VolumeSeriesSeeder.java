package com.power.posval.app.seed;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

/**
 * Generates and persists synthetic volume series for wind and solar assets.
 *
 * <p>MW ranges scale by granularity (each level doubles the previous):
 * <ul>
 *   <li>15-min: 17–20 MW ± 20% tolerance</li>
 *   <li>30-min: 34–40 MW ± 20%</li>
 *   <li>60-min: 68–80 MW ± 20%</li>
 *   <li>Daily:  68–80 MW ± 20%, energy = MW × 24h</li>
 *   <li>Monthly: 68–80 MW ± 20%, energy = MW × 30.5 × 24h</li>
 * </ul>
 *
 * <p>Core series (15-min, Jul 2026 → Jul 2028):
 * <ul>
 *   <li>FCST-WIND-01: wind 15-min</li>
 *   <li>FCST-SOLAR-01: solar 15-min</li>
 * </ul>
 *
 * <p>Multi-granularity series (Jul 2026 → Jan 2027):
 * <ul>
 *   <li>FCST-WIND-30M: wind at 30-min granularity</li>
 *   <li>FCST-SOLAR-60M: solar at 60-min granularity</li>
 *   <li>FCST-WIND-DAILY: wind daily average</li>
 *   <li>FCST-SOLAR-MONTHLY: solar monthly average</li>
 * </ul>
 */
public final class VolumeSeriesSeeder {

    private static final Logger log = LoggerFactory.getLogger(VolumeSeriesSeeder.class);

    public static final SeriesKey WIND_SERIES_KEY = new SeriesKey("FCST-WIND-01");
    public static final String WIND_ASSET_ID = "ASSET-WIND-01";

    public static final SeriesKey SOLAR_SERIES_KEY = new SeriesKey("FCST-SOLAR-01");
    public static final String SOLAR_ASSET_ID = "ASSET-SOLAR-01";

    // Multi-granularity series
    public static final SeriesKey WIND_30M_KEY = new SeriesKey("FCST-WIND-30M");
    public static final String WIND_30M_ASSET_ID = "ASSET-WIND-30M";

    public static final SeriesKey SOLAR_60M_KEY = new SeriesKey("FCST-SOLAR-60M");
    public static final String SOLAR_60M_ASSET_ID = "ASSET-SOLAR-60M";

    public static final SeriesKey WIND_DAILY_KEY = new SeriesKey("FCST-WIND-DAILY");
    public static final String WIND_DAILY_ASSET_ID = "ASSET-WIND-DAILY";

    public static final SeriesKey SOLAR_MONTHLY_KEY = new SeriesKey("FCST-SOLAR-MONTHLY");
    public static final String SOLAR_MONTHLY_ASSET_ID = "ASSET-SOLAR-MONTHLY";

    private static final TimeGranularity GRANULARITY = TimeGranularity.MIN_15;
    private static final long INTERVAL_MINUTES = GRANULARITY.getFixedDuration().toMinutes();
    private static final BigDecimal HOURS_PER_INTERVAL = BigDecimal.valueOf(INTERVAL_MINUTES)
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

    // MW base ranges per granularity (each level doubles the previous)
    private static final double MW_BASE_15M_LOW  = 17.0;
    private static final double MW_BASE_15M_HIGH = 20.0;
    private static final double MW_TOLERANCE      = 0.20; // ±20%

    private VolumeSeriesSeeder() {}

    /**
     * Generate a randomized MW value: pick base in [baseLow, baseHigh],
     * then apply ±{@link #MW_TOLERANCE} (20%) random variation.
     */
    private static double randomMw(Random random, double baseLow, double baseHigh) {
        double base = baseLow + random.nextDouble() * (baseHigh - baseLow);
        double factor = (1.0 - MW_TOLERANCE) + random.nextDouble() * (2.0 * MW_TOLERANCE);
        return Math.max(0, base * factor);
    }

    /**
     * Seed both wind and solar volume series.
     * Each month is persisted as a separate transaction to keep memory bounded.
     * Must be called within a context where the VolumeSeriesRepository is usable.
     */
    public static int[] seed(VolumeSeriesRepository repo) {
        ZonedDateTime start = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime end = ZonedDateTime.of(2028, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        log.info("Seeding wind volume series: {} ({} → {})", WIND_SERIES_KEY.value(), start, end);
        VolumeSeries windSeries = buildWindSeries(start, end);
        repo.save(windSeries);
        int windIntervals = windSeries.intervals().size();
        log.info("Wind series seeded: {} intervals", windIntervals);

        log.info("Seeding solar volume series: {} ({} → {})", SOLAR_SERIES_KEY.value(), start, end);
        VolumeSeries solarSeries = buildSolarSeries(start, end);
        repo.save(solarSeries);
        int solarIntervals = solarSeries.intervals().size();
        log.info("Solar series seeded: {} intervals", solarIntervals);

        // Multi-granularity series: Jul 2026 → Jan 2027
        ZonedDateTime mgStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime mgEnd = ZonedDateTime.of(2027, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        log.info("Seeding 30-min wind series: {} ({} → {})", WIND_30M_KEY.value(), mgStart, mgEnd);
        VolumeSeries wind30m = buildWind30mSeries(mgStart, mgEnd);
        repo.save(wind30m);
        log.info("Wind 30-min series seeded: {} intervals", wind30m.intervals().size());

        log.info("Seeding 60-min solar series: {} ({} → {})", SOLAR_60M_KEY.value(), mgStart, mgEnd);
        VolumeSeries solar60m = buildSolar60mSeries(mgStart, mgEnd);
        repo.save(solar60m);
        log.info("Solar 60-min series seeded: {} intervals", solar60m.intervals().size());

        log.info("Seeding daily wind series: {} ({} → {})", WIND_DAILY_KEY.value(), mgStart, mgEnd);
        VolumeSeries windDaily = buildWindDailySeries(mgStart, mgEnd);
        repo.save(windDaily);
        log.info("Wind daily series seeded: {} intervals", windDaily.intervals().size());

        log.info("Seeding monthly solar series: {} ({} → {})", SOLAR_MONTHLY_KEY.value(), mgStart, mgEnd);
        VolumeSeries solarMonthly = buildSolarMonthlySeries(mgStart, mgEnd);
        repo.save(solarMonthly);
        log.info("Solar monthly series seeded: {} intervals", solarMonthly.intervals().size());

        return new int[]{windIntervals, solarIntervals,
            wind30m.intervals().size(), solar60m.intervals().size(),
            windDaily.intervals().size(), solarMonthly.intervals().size()};
    }

    /** Wind 15-min: 17–20 MW ± 20%. */
    private static VolumeSeries buildWindSeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(42);
        ZonedDateTime cursor = start;
        int seq = 0;

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(INTERVAL_MINUTES);
            double mw = randomMw(random, MW_BASE_15M_LOW, MW_BASE_15M_HIGH);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(HOURS_PER_INTERVAL)
                    .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                    new UUID(0L, ++seq),
                    cursor.toInstant(), next.toInstant(),
                    volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(WIND_SERIES_KEY)
                .seriesType(SeriesType.FORECAST)
                .assetId(WIND_ASSET_ID)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(GRANULARITY)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }

    /** Solar 15-min: 17–20 MW ± 20%. */
    private static VolumeSeries buildSolarSeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(99);
        ZonedDateTime cursor = start;
        int seq = 0;

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(INTERVAL_MINUTES);
            double mw = randomMw(random, MW_BASE_15M_LOW, MW_BASE_15M_HIGH);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(HOURS_PER_INTERVAL)
                    .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                    new UUID(1L, ++seq),
                    cursor.toInstant(), next.toInstant(),
                    volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(SOLAR_SERIES_KEY)
                .seriesType(SeriesType.FORECAST)
                .assetId(SOLAR_ASSET_ID)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(GRANULARITY)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }

    // --- Multi-granularity series builders ---

    /** Wind 30-min: 34–40 MW ± 20% (2× the 15-min base). */
    private static VolumeSeries buildWind30mSeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(201);
        ZonedDateTime cursor = start;
        int seq = 0;
        long stepMinutes = 30;
        BigDecimal hoursPerInterval = BigDecimal.valueOf(stepMinutes)
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP); // 0.5000

        double baseLow  = MW_BASE_15M_LOW  * 2; // 34
        double baseHigh = MW_BASE_15M_HIGH * 2; // 40

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(stepMinutes);
            double mw = randomMw(random, baseLow, baseHigh);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(hoursPerInterval)
                    .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                    new UUID(2L, ++seq),
                    cursor.toInstant(), next.toInstant(),
                    volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(WIND_30M_KEY)
                .seriesType(SeriesType.FORECAST)
                .assetId(WIND_30M_ASSET_ID)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(TimeGranularity.MIN_30)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }

    /** Solar 60-min: 68–80 MW ± 20% (2× the 30-min base, i.e. 4× 15-min). */
    private static VolumeSeries buildSolar60mSeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(302);
        ZonedDateTime cursor = start;
        int seq = 0;
        long stepMinutes = 60;
        BigDecimal hoursPerInterval = BigDecimal.ONE;

        double baseLow  = MW_BASE_15M_LOW  * 4; // 68
        double baseHigh = MW_BASE_15M_HIGH * 4; // 80

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(stepMinutes);
            double mw = randomMw(random, baseLow, baseHigh);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(hoursPerInterval)
                    .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                    new UUID(3L, ++seq),
                    cursor.toInstant(), next.toInstant(),
                    volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(SOLAR_60M_KEY)
                .seriesType(SeriesType.FORECAST)
                .assetId(SOLAR_60M_ASSET_ID)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(TimeGranularity.HOURLY)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }

    /** Wind daily: 68–80 MW ± 20%, energy = MW × 24h. */
    private static VolumeSeries buildWindDailySeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(403);
        ZonedDateTime cursor = start;
        int seq = 0;

        double baseLow  = MW_BASE_15M_LOW  * 4; // 68
        double baseHigh = MW_BASE_15M_HIGH * 4; // 80

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusDays(1);
            double mw = randomMw(random, baseLow, baseHigh);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal hoursInDay = BigDecimal.valueOf(24);
            BigDecimal energy = volume.multiply(hoursInDay)
                    .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                    new UUID(4L, ++seq),
                    cursor.toInstant(), next.toInstant(),
                    volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(WIND_DAILY_KEY)
                .seriesType(SeriesType.FORECAST)
                .assetId(WIND_DAILY_ASSET_ID)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(TimeGranularity.DAILY)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }

    /** Solar monthly: 68–80 MW ± 20%, energy = MW × 30.5 × 24h (732 hours). */
    private static VolumeSeries buildSolarMonthlySeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(504);
        int seq = 0;

        double baseLow  = MW_BASE_15M_LOW  * 4; // 68
        double baseHigh = MW_BASE_15M_HIGH * 4; // 80
        BigDecimal hoursPerMonth = BigDecimal.valueOf(30.5 * 24); // 732

        YearMonth cursor = YearMonth.from(start.toLocalDate());
        YearMonth endMonth = YearMonth.from(end.toLocalDate());

        while (cursor.isBefore(endMonth)) {
            ZonedDateTime monthStart = cursor.atDay(1).atStartOfDay(ZoneOffset.UTC);
            YearMonth nextMonth = cursor.plusMonths(1);
            ZonedDateTime monthEnd = nextMonth.atDay(1).atStartOfDay(ZoneOffset.UTC);

            double mw = randomMw(random, baseLow, baseHigh);
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(hoursPerMonth)
                    .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                    new UUID(5L, ++seq),
                    monthStart.toInstant(), monthEnd.toInstant(),
                    volume, energy, 1, null));
            cursor = nextMonth;
        }

        return DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(SOLAR_MONTHLY_KEY)
                .seriesType(SeriesType.FORECAST)
                .assetId(SOLAR_MONTHLY_ASSET_ID)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(TimeGranularity.MONTHLY)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }
}
