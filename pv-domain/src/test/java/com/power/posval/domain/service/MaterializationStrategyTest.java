package com.power.posval.domain.service;

import com.power.posval.domain.event.VolumePublished;
import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        assertInstanceOf(VolumePublished.class, events.get(0));
        var published = (VolumePublished) events.get(0);
        assertEquals("FULL", published.scope());
        assertEquals(VolumeLayer.VOLUME, published.layer());
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
}
