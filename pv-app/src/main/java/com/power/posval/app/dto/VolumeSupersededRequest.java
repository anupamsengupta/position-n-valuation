package com.power.posval.app.dto;

import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * REST DTO for triggering a {@link VolumeSuperseded} event through the outbox → Kafka pipeline.
 * Used for testing the full production flow (cache invalidation → position lookup → revaluation).
 */
public record VolumeSupersededRequest(
        String seriesKey,            // e.g., "FORECAST-WIND-DE-LU"
        String layer,                // FORECAST, PROFILE, METERED_ACTUAL
        String seriesType,           // nullable — FORECAST_SERIES, PROFILE_SERIES, etc.
        String affectedRangeStart,   // ISO-8601 zoned datetime, e.g. "2025-03-01T00:00:00+01:00"
        String affectedRangeEnd,     // ISO-8601 zoned datetime
        String deliveryTimezone,     // e.g., "Europe/Berlin"
        Long oldVersionId,           // nullable for first publication
        long newVersionId,
        String qualityState          // PROVISIONAL, VALIDATED, FINAL
) {
    public VolumeSuperseded toEvent() {
        ZoneId tz = ZoneId.of(deliveryTimezone);
        ZonedDateTime start = ZonedDateTime.parse(affectedRangeStart).withZoneSameInstant(tz);
        ZonedDateTime end = ZonedDateTime.parse(affectedRangeEnd).withZoneSameInstant(tz);

        return new VolumeSuperseded(
                new SeriesKey(seriesKey),
                VolumeLayer.valueOf(layer),
                seriesType != null ? SeriesType.valueOf(seriesType) : null,
                new DeliveryPeriod(start, end, tz),
                oldVersionId,
                newVersionId,
                QualityState.valueOf(qualityState),
                Instant.now());
    }
}
