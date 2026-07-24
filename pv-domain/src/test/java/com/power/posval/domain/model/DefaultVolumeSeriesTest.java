package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefaultVolumeSeriesTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void builderCreatesValidProfileSeries() {
        var series = profileBuilder().build();

        assertEquals(SeriesType.PROFILE, series.seriesType());
        assertEquals("LEG-1", series.tradeLegId());
        assertNull(series.assetId());
        assertEquals(QualityState.CURRENT, series.qualityState());
    }

    @Test
    void builderCreatesForecastSeriesWithAsset() {
        var series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("FCST-WP-NORDSEE"))
            .seriesType(SeriesType.FORECAST)
            .assetId("WP-NORDSEE")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(testDeliveryPeriod())
            .transactionTime(Instant.now())
            .build();

        assertEquals(SeriesType.FORECAST, series.seriesType());
        assertEquals("WP-NORDSEE", series.assetId());
        assertNull(series.tradeLegId());
    }

    @Test
    void forecastWithoutAssetThrows() {
        var builder = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("FCST-X"))
            .seriesType(SeriesType.FORECAST)
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(testDeliveryPeriod())
            .transactionTime(Instant.now());

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void profileWithoutTradeLegThrows() {
        var builder = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-X"))
            .seriesType(SeriesType.PROFILE)
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(testDeliveryPeriod())
            .transactionTime(Instant.now());

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void intervalsAreOrderedByStart() {
        var late = new DefaultVolumeInterval(UUID.randomUUID(),
            Instant.parse("2025-03-01T01:00:00Z"), Instant.parse("2025-03-01T02:00:00Z"),
            BigDecimal.TEN, BigDecimal.ONE, 1, null);
        var early = new DefaultVolumeInterval(UUID.randomUUID(),
            Instant.parse("2025-03-01T00:00:00Z"), Instant.parse("2025-03-01T01:00:00Z"),
            BigDecimal.TEN, BigDecimal.ONE, 1, null);

        var series = profileBuilder()
            .intervals(List.of(late, early))
            .build();

        var first = series.intervals().getFirst();
        assertEquals(early.intervalStart(), first.intervalStart());
    }

    @Test
    void intervalsSetIsUnmodifiable() {
        var series = profileBuilder().build();
        assertThrows(UnsupportedOperationException.class,
            () -> series.intervals().add(null));
    }

    private DefaultVolumeSeries.Builder profileBuilder() {
        return DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-T7788-1"))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.MIN_15)
            .deliveryPeriod(testDeliveryPeriod())
            .transactionTime(Instant.now());
    }

    private DeliveryPeriod testDeliveryPeriod() {
        return new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET);
    }
}
