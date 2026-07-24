package com.power.posval.domain.service;

import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.cache.CachedInterval;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CacheInvalidationHandlerTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void onVolumeSuperseded_callsInvalidate() {
        var invalidations = new ArrayList<String>();

        VolumeCache stubCache = new StubVolumeCache() {
            @Override
            public void invalidate(String tenantId, String seriesKey, DeliveryRange range) {
                invalidations.add(tenantId + ":" + seriesKey);
            }
        };

        var handler = new CacheInvalidationHandler(stubCache, null);

        handler.onVolumeSuperseded(new VolumeSuperseded(
            new SeriesKey("FCST-WP-NORDSEE"),
            VolumeLayer.VOLUME,
            SeriesType.FORECAST,
            new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET),
                CET),
            1L, 2L,
            QualityState.SUPERSEDED,
            Instant.now()));

        assertEquals(1, invalidations.size());
    }

    @Test
    void onVolumeReferenceChanged_invalidatesBothSeries() {
        var invalidations = new ArrayList<String>();

        VolumeCache stubCache = new StubVolumeCache() {
            @Override
            public void invalidateAll(String tenantId, String seriesKey) {
                invalidations.add(tenantId + ":" + seriesKey);
            }
        };

        var handler = new CacheInvalidationHandler(stubCache, null);
        handler.onVolumeReferenceChanged("TN_0042", "OLD-SERIES", "NEW-SERIES");

        assertEquals(2, invalidations.size());
        assertTrue(invalidations.contains("TN_0042:OLD-SERIES"));
        assertTrue(invalidations.contains("TN_0042:NEW-SERIES"));
    }

    /** Minimal stub implementation of VolumeCache. */
    private static class StubVolumeCache implements VolumeCache {
        @Override public Optional<CachedInterval> get(String t, String s, Instant i, boolean c) { return Optional.empty(); }
        @Override public List<CachedInterval> getAll(String t, String s, List<Instant> i) { return List.of(); }
        @Override public void put(String t, String s, Instant i, CachedInterval v) {}
        @Override public void putAll(String t, String s, Map<Instant, CachedInterval> v) {}
        @Override public void invalidate(String t, String s, DeliveryRange r) {}
        @Override public void invalidateAll(String t, String s) {}
    }
}
