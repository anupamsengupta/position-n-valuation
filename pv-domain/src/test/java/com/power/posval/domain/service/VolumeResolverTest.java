package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.repository.MeteredActualRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.port.DefaultNumericPrecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VolumeResolverTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void profileResolver_appliesMultiplier() {
        var interval = new DefaultVolumeInterval(UUID.randomUUID(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T01:00:00Z"),
            new BigDecimal("100.0"), new BigDecimal("100.0"), 1, null);

        var series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-TEST"))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.HOURLY)
            .deliveryPeriod(testDeliveryPeriod())
            .transactionTime(Instant.now())
            .intervals(List.of(interval))
            .build();

        VolumeSeriesRepository repo = stubRepo(series);
        var resolver = new ProfileResolver(repo, new DefaultNumericPrecision());

        var ref = VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId("LEG-1")
            .tradeId("T-7788")
            .multiplier(new BigDecimal("0.5"))
            .volumeSeriesKey(new SeriesKey("VS-TEST"))
            .effectiveFrom(ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET))
            .effectiveTo(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, CET))
            .build();

        List<VolumeRecord> records = resolver.resolve(ref,
            Instant.parse("2025-03-01T00:00:00Z"), Instant.parse("2025-04-01T00:00:00Z"),
            ResolutionPurpose.FORWARD);

        assertEquals(1, records.size());
        assertEquals(0, new BigDecimal("50.0").compareTo(records.get(0).volume()));
    }

    @Test
    void profileResolver_emptyOnMissingSeries() {
        VolumeSeriesRepository repo = stubRepo(null);
        var resolver = new ProfileResolver(repo, new DefaultNumericPrecision());

        var ref = VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId("LEG-1")
            .tradeId("T-7788")
            .multiplier(BigDecimal.ONE)
            .volumeSeriesKey(new SeriesKey("NONEXISTENT"))
            .effectiveFrom(ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET))
            .effectiveTo(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, CET))
            .build();

        List<VolumeRecord> records = resolver.resolve(ref,
            Instant.parse("2025-03-01T00:00:00Z"), Instant.parse("2025-04-01T00:00:00Z"),
            ResolutionPurpose.FORWARD);

        assertTrue(records.isEmpty());
    }

    @Test
    void forecastResolver_usesForwardPurpose() {
        var interval = new DefaultVolumeInterval(UUID.randomUUID(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T01:00:00Z"),
            new BigDecimal("200.0"), new BigDecimal("200.0"), 1, null);

        var series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("FCST-WP"))
            .seriesType(SeriesType.FORECAST)
            .assetId("WP-NORDSEE")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.HOURLY)
            .deliveryPeriod(testDeliveryPeriod())
            .transactionTime(Instant.now())
            .intervals(List.of(interval))
            .build();

        VolumeSeriesRepository repo = stubRepo(series);
        MeteredActualRepository meteredRepo = (t, k) -> Optional.empty();
        var resolver = new ForecastResolver(repo, meteredRepo, new DefaultNumericPrecision());

        var ref = VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId("LEG-1")
            .tradeId("T-7788")
            .multiplier(new BigDecimal("0.3"))
            .volumeSeriesKey(new SeriesKey("FCST-WP"))
            .effectiveFrom(ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET))
            .effectiveTo(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, CET))
            .build();

        List<VolumeRecord> records = resolver.resolve(ref,
            Instant.parse("2025-03-01T00:00:00Z"), Instant.parse("2025-04-01T00:00:00Z"),
            ResolutionPurpose.FORWARD);

        assertEquals(1, records.size());
        assertEquals(0, new BigDecimal("60.0").compareTo(records.get(0).volume()));
    }

    private DeliveryPeriod testDeliveryPeriod() {
        return new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET);
    }

    private VolumeSeriesRepository stubRepo(VolumeSeries series) {
        return new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) {}
            @Override public Optional<VolumeSeries> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String t, String k) {
                return Optional.ofNullable(series);
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return List.of(); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return List.of(); }
            @Override public boolean existsByTradeIdAndTradeVersion(String t, int v) { return false; }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) {}
        };
    }
}
