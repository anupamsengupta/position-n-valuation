package com.power.posval.domain.service;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.DomainEventPublisher;

/**
 * Sealed materialization strategy for volume series interval generation.
 * Pattern #11, FR-056, S3/S6b.
 */
public sealed interface MaterializationStrategy
    permits EagerStrategy, RollingHorizonStrategy, ChunkStrategy {

    /**
     * Materialize intervals for the given series.
     * @param series    target volume series
     * @param writer    batch writer for persistence (Pattern #20)
     * @param publisher event publisher for VolumePublished/ChunkMaterialized
     */
    void materialize(VolumeSeries series,
                     Object writer,
                     DomainEventPublisher publisher);
}
