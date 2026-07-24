package com.power.posval.domain.service;

import com.power.posval.domain.event.VolumeChunkMaterialized;
import com.power.posval.domain.model.MaterializationStatus;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.ChunkEnqueuer;
import com.power.posval.domain.port.event.DomainEventPublisher;

import java.time.Instant;
import java.time.YearMonth;

/**
 * Rolling-horizon: materialize M+1 through M+horizonMonths immediately,
 * enqueue remaining months as chunks.
 * V3.0 §4.2: long-tenor trades (PPAs, multi-year bilateral).
 * Pattern #11.
 */
public record RollingHorizonStrategy(
    int horizonMonths,
    ChunkEnqueuer enqueuer
) implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            Object writer,
                            DomainEventPublisher publisher) {
        YearMonth now = YearMonth.now();
        YearMonth horizonEnd = now.plusMonths(horizonMonths);
        YearMonth seriesEnd = YearMonth.from(series.deliveryPeriod().end().minusNanos(1));

        // Near-term intervals (within horizon) are materialized eagerly.
        // The writer (BatchWriter) handles persistence in the calling context.
        int nearTermIntervals = series.intervals().stream()
            .filter(i -> {
                YearMonth m = YearMonth.from(
                    i.intervalStart().atZone(series.deliveryPeriod().deliveryTimezone()));
                return !m.isAfter(horizonEnd);
            })
            .mapToInt(i -> 1)
            .sum();

        // Far-dated months: enqueue as async chunks
        YearMonth month = horizonEnd.plusMonths(1);
        while (!month.isAfter(seriesEnd)) {
            enqueuer.enqueue(series.seriesKey(), month);
            month = month.plusMonths(1);
        }

        publisher.publish(new VolumeChunkMaterialized(
            series.seriesKey(),
            VolumeLayer.VOLUME,
            series.seriesType(),
            now,
            series.versionId(),
            nearTermIntervals,
            month.isAfter(seriesEnd)
                ? MaterializationStatus.FULL
                : MaterializationStatus.PARTIAL,
            Instant.now()));
    }
}
