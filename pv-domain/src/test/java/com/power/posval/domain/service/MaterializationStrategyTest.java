package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.event.ChunkEnqueuer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MaterializationStrategyTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void eagerStrategy_publishesCompleteEvent() {
        var events = new ArrayList<Object>();
        var strategy = new EagerStrategy();

        strategy.materialize(testSeries(), null, events::add);

        assertEquals(1, events.size());
    }

    @Test
    void chunkStrategy_enqueuesAllMonths() {
        var enqueued = new ArrayList<YearMonth>();
        ChunkEnqueuer enq = (key, month) -> enqueued.add(month);
        var events = new ArrayList<Object>();

        var strategy = new ChunkStrategy(enq);
        strategy.materialize(threeMonthSeries(), null, events::add);

        assertEquals(3, enqueued.size());
        assertEquals(YearMonth.of(2025, 3), enqueued.get(0));
        assertEquals(YearMonth.of(2025, 5), enqueued.get(2));
    }

    @Test
    void rollingHorizonStrategy_enqueuesFarDatedMonths() {
        var enqueued = new ArrayList<YearMonth>();
        ChunkEnqueuer enq = (key, month) -> enqueued.add(month);
        var events = new ArrayList<Object>();

        // horizon=1 month means only current+1 month is eager, rest enqueued
        var strategy = new RollingHorizonStrategy(1, enq);

        // Create a series spanning 12 months from now
        YearMonth now = YearMonth.now();
        var series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-TEST"))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.HOURLY)
            .deliveryPeriod(new DeliveryPeriod(
                now.atDay(1).atStartOfDay(CET),
                now.plusMonths(12).atDay(1).atStartOfDay(CET), CET))
            .transactionTime(Instant.now())
            .build();

        strategy.materialize(series, null, events::add);

        // Far-dated months should be enqueued (those beyond horizon)
        assertFalse(enqueued.isEmpty());
        assertEquals(1, events.size());
    }

    private VolumeSeries testSeries() {
        return DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-TEST"))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.HOURLY)
            .deliveryPeriod(new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET))
            .transactionTime(Instant.now())
            .build();
    }

    private VolumeSeries threeMonthSeries() {
        return DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-TEST"))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.HOURLY)
            .deliveryPeriod(new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0, CET), CET))
            .transactionTime(Instant.now())
            .build();
    }
}
