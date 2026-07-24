package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VolumeSeriesFactoryTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void createProfile_createsSeriesWithProfileType() {
        var saved = new ArrayList<VolumeSeries>();
        var factory = new VolumeSeriesFactory(stubRepo(saved, null));

        var interval = new DefaultVolumeInterval(UUID.randomUUID(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T01:00:00Z"),
            BigDecimal.TEN, BigDecimal.ONE, 1, null);

        VolumeSeries series = factory.createProfile(
            "TN_0042", "LEG-1", "T-7788", 1,
            testDeliveryPeriod(), VolumeUnit.MW_CAPACITY,
            TimeGranularity.HOURLY, List.of(interval));

        assertEquals(SeriesType.PROFILE, series.seriesType());
        assertEquals("LEG-1", series.tradeLegId());
        assertTrue(series.seriesKey().value().contains("T-7788"));
        assertEquals(1, series.intervals().size());
        assertEquals(1, saved.size());
    }

    @Test
    void createOrGetForecast_returnsExistingIfFound() {
        var existing = DefaultVolumeSeries.builder()
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

        var factory = new VolumeSeriesFactory(stubRepo(new ArrayList<>(), existing));

        VolumeSeries result = factory.createOrGetForecast(
            "TN_0042", "WP-NORDSEE", testDeliveryPeriod(),
            VolumeUnit.MW_CAPACITY, TimeGranularity.MIN_15);

        assertSame(existing, result);
    }

    @Test
    void createOrGetForecast_createsNewIfNotFound() {
        var saved = new ArrayList<VolumeSeries>();
        var factory = new VolumeSeriesFactory(stubRepo(saved, null));

        VolumeSeries result = factory.createOrGetForecast(
            "TN_0042", "WP-NORDSEE", testDeliveryPeriod(),
            VolumeUnit.MW_CAPACITY, TimeGranularity.MIN_15);

        assertEquals(SeriesType.FORECAST, result.seriesType());
        assertEquals("WP-NORDSEE", result.assetId());
        assertEquals(1, saved.size());
    }

    @Test
    void createProfile_nullTradeLegThrows() {
        var factory = new VolumeSeriesFactory(stubRepo(new ArrayList<>(), null));
        assertThrows(NullPointerException.class, () ->
            factory.createProfile("TN", null, "T-1", 1,
                testDeliveryPeriod(), VolumeUnit.MW_CAPACITY,
                TimeGranularity.HOURLY, List.of()));
    }

    private DeliveryPeriod testDeliveryPeriod() {
        return new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET);
    }

    private VolumeSeriesRepository stubRepo(List<VolumeSeries> saved, VolumeSeries existing) {
        return new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) { saved.add(s); }
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String t, String k) {
                return Optional.ofNullable(existing);
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String t, int v) { return false; }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };
    }
}
