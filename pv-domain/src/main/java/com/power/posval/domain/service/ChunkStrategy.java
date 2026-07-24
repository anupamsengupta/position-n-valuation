package com.power.posval.domain.service;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.DomainEventPublisher;

/**
 * Chunk materialization: month-by-month via virtual threads.
 * V3.0 §4.3: parallel chunk processing.
 * Pattern #11.
 */
public record ChunkStrategy() implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            Object writer,
                            DomainEventPublisher publisher) {
        // Full implementation deferred — depends on BatchWriter in pv-persistence
    }
}
