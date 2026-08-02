package com.power.posval.integration.support;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates synthetic volume series for integration tests.
 * Matching the pattern from FiveYearTradeIntegrationTest.
 */
public final class VolumeSeriesGenerator {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    // Asset 1: Wind, 15-min, 80 MW
    public static final SeriesKey ASSET1_SERIES_KEY = new SeriesKey("FCST-ASSET1");
    public static final String ASSET1_ID = "ASSET-WIND-01";

    // Asset 2: Solar, 30-min, 45 MW
    public static final SeriesKey ASSET2_SERIES_KEY = new SeriesKey("FCST-ASSET2");
    public static final String ASSET2_ID = "ASSET-SOLAR-01";

    private VolumeSeriesGenerator() {}

    /**
     * Create Asset 1 (wind) volume series: 1 month (Jul 2026), 15-min, 80 MW.
     */
    public static VolumeSeries createAsset1(VolumeSeriesRepository repo) {
        ZonedDateTime start = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, CET);
        ZonedDateTime end = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, CET);
        VolumeSeries series = buildWindSeries(
            ASSET1_SERIES_KEY, ASSET1_ID, start, end, 15, 80.0);
        repo.save(series);
        return series;
    }

    /**
     * Create Asset 2 (solar) volume series: 1 month (Jul 2026), 30-min, 45 MW.
     */
    public static VolumeSeries createAsset2(VolumeSeriesRepository repo) {
        ZonedDateTime start = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, CET);
        ZonedDateTime end = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, CET);
        VolumeSeries series = buildSolarSeries(
            ASSET2_SERIES_KEY, ASSET2_ID, start, end, 30, 45.0);
        repo.save(series);
        return series;
    }

    private static VolumeSeries buildWindSeries(SeriesKey key, String assetId,
                                                  ZonedDateTime start, ZonedDateTime end,
                                                  int intervalMinutes, double capacityMw) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(42);
        ZonedDateTime cursor = start;
        int seq = 0;
        BigDecimal hoursPerInterval = BigDecimal.valueOf(intervalMinutes)
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(intervalMinutes);
            // Wind pattern: sinusoidal + noise, higher at night
            double hourBase = 45.0 + 20.0 * Math.sin(cursor.getHour() * Math.PI / 12.0);
            double mw = Math.max(0, Math.min(capacityMw,
                hourBase + random.nextGaussian() * 8.0));
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(hoursPerInterval)
                .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                new UUID(0L, ++seq),
                cursor.toInstant(), next.toInstant(),
                volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(key)
            .seriesType(SeriesType.FORECAST)
            .assetId(assetId)
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(new DeliveryPeriod(start, end, CET))
            .qualityState(QualityState.CURRENT)
            .transactionTime(Instant.now())
            .intervals(intervals)
            .build();
    }

    private static VolumeSeries buildSolarSeries(SeriesKey key, String assetId,
                                                   ZonedDateTime start, ZonedDateTime end,
                                                   int intervalMinutes, double capacityMw) {
        var intervals = new ArrayList<VolumeInterval>();
        var random = new Random(99);
        ZonedDateTime cursor = start;
        int seq = 0;
        BigDecimal hoursPerInterval = BigDecimal.valueOf(intervalMinutes)
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(intervalMinutes);
            // Solar pattern: daytime peak, zero at night
            int hour = cursor.getHour();
            double mw;
            if (hour >= 6 && hour <= 20) {
                double peakFactor = Math.sin((hour - 6) * Math.PI / 14.0);
                mw = Math.max(0, Math.min(capacityMw,
                    capacityMw * peakFactor + random.nextGaussian() * 5.0));
            } else {
                mw = 0;
            }
            BigDecimal volume = BigDecimal.valueOf(mw).setScale(1, RoundingMode.HALF_UP);
            BigDecimal energy = volume.multiply(hoursPerInterval)
                .setScale(3, RoundingMode.HALF_UP);

            intervals.add(new DefaultVolumeInterval(
                new UUID(1L, ++seq),
                cursor.toInstant(), next.toInstant(),
                volume, energy, 1, null));
            cursor = next;
        }

        return DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(key)
            .seriesType(SeriesType.FORECAST)
            .assetId(assetId)
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_30)
            .deliveryPeriod(new DeliveryPeriod(start, end, CET))
            .qualityState(QualityState.CURRENT)
            .transactionTime(Instant.now())
            .intervals(intervals)
            .build();
    }
}
