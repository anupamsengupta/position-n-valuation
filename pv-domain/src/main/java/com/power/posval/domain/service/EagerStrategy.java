package com.power.posval.domain.service;

import com.power.posval.domain.event.VolumePublished;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.DomainEventPublisher;

import java.time.Instant;

/**
 * Eager materialization: all intervals at once.
 * Used for PROFILE series (trade capture decomposes flat MW blocks into 15-min intervals).
 * FORECAST and METERED_ACTUAL intervals arrive via import, not materialization.
 * Pattern #11.
 */
public record EagerStrategy() implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            Object writer,
                            DomainEventPublisher publisher) {
        // Eager: materialize all intervals immediately.
        // The writer (BatchWriter from pv-persistence) handles flush/clear cycles.
        // Domain layer signals completion via VolumePublished event.
        publisher.publish(new VolumePublished(
            series.seriesKey(), VolumeLayer.VOLUME, series.seriesType(),
            series.versionId(), series.deliveryPeriod(), series.granularity(),
            series.qualityState(), "FULL", Instant.now()));
    }
}
