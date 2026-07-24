package com.power.posval.domain.service;

import com.power.posval.domain.event.VolumeChunkMaterialized;
import com.power.posval.domain.model.MaterializationStatus;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.DomainEventPublisher;

import java.time.Instant;
import java.time.YearMonth;

/**
 * Eager materialization: all intervals at once.
 * V3.0 §4.1: short-tenor trades.
 * Pattern #11.
 */
public record EagerStrategy() implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            Object writer,
                            DomainEventPublisher publisher) {
        // Eager: materialize all intervals immediately.
        // The writer (BatchWriter from pv-persistence) handles flush/clear cycles.
        // Domain layer signals completion via event.
        publisher.publish(new VolumeChunkMaterialized(
            series.seriesKey(),
            VolumeLayer.VOLUME,
            series.seriesType(),
            YearMonth.from(series.deliveryPeriod().start()),
            series.versionId(),
            series.intervals().size(),
            MaterializationStatus.FULL,
            Instant.now()));
    }
}
