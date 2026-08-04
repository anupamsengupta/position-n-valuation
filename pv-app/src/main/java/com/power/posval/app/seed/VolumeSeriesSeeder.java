package com.power.posval.app.seed;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

/**
 * Generates and persists synthetic volume series for wind and solar assets.
 * 15-minute granularity, UTC timezone, Jul 2026 → Jul 2028 (2 years).
 *
 * Wind: sinusoidal + noise, 80 MW capacity, higher at night.
 * Solar: daytime peak, zero at night, 45 MW capacity.
 */
public final class VolumeSeriesSeeder {

    private static final Logger log = LoggerFactory.getLogger(VolumeSeriesSeeder.class);

    public static final SeriesKey WIND_SERIES_KEY = new SeriesKey("FCST-WIND-01");
    public static final String WIND_ASSET_ID = "ASSET-WIND-01";

    public static final SeriesKey SOLAR_SERIES_KEY = new SeriesKey("FCST-SOLAR-01");
    public static final String SOLAR_ASSET_ID = "ASSET-SOLAR-01";

    private static final int INTERVAL_MINUTES = 15;
    private static final BigDecimal HOURS_PER_INTERVAL = BigDecimal.valueOf(INTERVAL_MINUTES)
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP); // 0.2500

    private VolumeSeriesSeeder() {}

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

        return new int[]{windIntervals, solarIntervals};
    }

    private static VolumeSeries buildWindSeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(42);
        ZonedDateTime cursor = start;
        int seq = 0;
        double capacityMw = 80.0;

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(INTERVAL_MINUTES);
            // Wind pattern: sinusoidal + noise, higher at night
            double hourBase = 45.0 + 20.0 * Math.sin(cursor.getHour() * Math.PI / 12.0);
            double mw = Math.max(0, Math.min(capacityMw,
                    hourBase + random.nextGaussian() * 8.0));
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
                .granularity(TimeGranularity.MIN_15)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }

    private static VolumeSeries buildSolarSeries(ZonedDateTime start, ZonedDateTime end) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(99);
        ZonedDateTime cursor = start;
        int seq = 0;
        double capacityMw = 45.0;

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(INTERVAL_MINUTES);
            int hour = cursor.getHour();
            double mw;
            if (hour >= 6 && hour <= 20) {
                // Solar pattern: daytime peak
                double peakFactor = Math.sin((hour - 6) * Math.PI / 14.0);
                mw = Math.max(0, Math.min(capacityMw,
                        capacityMw * peakFactor + random.nextGaussian() * 5.0));
            } else {
                mw = 0;
            }
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
                .granularity(TimeGranularity.MIN_15)
                .deliveryPeriod(new DeliveryPeriod(start, end, ZoneOffset.UTC))
                .qualityState(QualityState.CURRENT)
                .transactionTime(Instant.now())
                .intervals(intervals)
                .build();
    }
}
