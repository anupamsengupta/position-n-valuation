package com.power.posval.domain.event;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.Money;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.DefaultNumericPrecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventSmokeTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.now();

    @Test
    void volumePublished() {
        var dp = new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET);
        var event = new VolumePublished(
            new SeriesKey("VS-1"), VolumeLayer.VOLUME, SeriesType.FORECAST,
            1L, dp, TimeGranularity.HOURLY, QualityState.CURRENT, "FULL", NOW);
        assertEquals("VS-1", event.seriesKey().value());
        assertEquals(1L, event.versionId());
        assertEquals("FULL", event.scope());
    }

    @Test
    void volumeSuperseded() {
        var dp = new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET);
        var event = new VolumeSuperseded(
            new SeriesKey("VS-1"), VolumeLayer.VOLUME, null,
            dp, null, 2L, QualityState.SUPERSEDED, NOW);
        assertNull(event.seriesType());
        assertNull(event.oldVersionId());
        assertEquals(2L, event.newVersionId());
    }

    @Test
    void volumeChunkMaterialized() {
        var event = new VolumeChunkMaterialized(
            new SeriesKey("VS-1"), VolumeLayer.VOLUME, SeriesType.FORECAST,
            YearMonth.of(2025, 3), 1L, 744, MaterializationStatus.FULL, NOW);
        assertEquals(YearMonth.of(2025, 3), event.chunkMonth());
        assertEquals(744, event.intervalCount());
    }

    @Test
    void settlementComputed() {
        var np = new DefaultNumericPrecision();
        var event = new SettlementComputed(
            UUID.randomUUID(),
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 3, 1, 1, 0, 0, 0, CET),
            Money.eur(new BigDecimal("1234.56"), np),
            "PROVISIONAL",
            Set.of("leaf1", "leaf2"),
            Map.of("EPEX_SPOT", 5L),
            NOW);
        assertEquals("PROVISIONAL", event.status());
        assertEquals(2, event.activeLeaves().size());
    }
}
