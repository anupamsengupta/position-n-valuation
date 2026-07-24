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
 * Chunk materialization: month-by-month via Kafka enqueue.
 * V3.0 §4.3: parallel chunk processing.
 * Pattern #11.
 */
public record ChunkStrategy(ChunkEnqueuer enqueuer) implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            Object writer,
                            DomainEventPublisher publisher) {
        // Enqueue each delivery month as a separate chunk for parallel processing
        YearMonth start = YearMonth.from(series.deliveryPeriod().start());
        YearMonth end = YearMonth.from(series.deliveryPeriod().end().minusNanos(1));
        YearMonth month = start;

        while (!month.isAfter(end)) {
            enqueuer.enqueue(series.seriesKey(), month);
            month = month.plusMonths(1);
        }

        publisher.publish(new VolumeChunkMaterialized(
            series.seriesKey(),
            VolumeLayer.VOLUME,
            series.seriesType(),
            start,
            series.versionId(),
            0,
            MaterializationStatus.PARTIAL,
            Instant.now()));
    }
}
