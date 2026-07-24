package com.power.posval.domain.service;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.DomainEventPublisher;

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
        // Full implementation deferred — depends on BatchWriter in pv-persistence
    }
}
