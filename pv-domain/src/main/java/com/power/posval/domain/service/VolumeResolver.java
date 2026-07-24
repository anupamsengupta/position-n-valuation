package com.power.posval.domain.service;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;

import java.util.List;

/**
 * Sealed volume resolution strategy. Both implementations apply the same
 * formula: volume × multiplier (D-11). The seal provides exhaustive dispatch
 * and type-specific logging/metrics, not behavioral branching.
 * Pattern #9, FR-051, S3.
 */
public sealed interface VolumeResolver permits ProfileResolver, ForecastResolver {

    /**
     * Resolve volume for a trade-leg over an interval range.
     * @param ref           the VolumeReference linking trade to series
     * @param intervalRange half-open [start, end) in market-local time
     * @param purpose       FORWARD or SETTLEMENT — determines series selection (FR-048e)
     * @return resolved volume records with multiplier applied
     */
    List<VolumeRecord> resolve(VolumeReference ref,
                                DeliveryRange intervalRange,
                                ResolutionPurpose purpose);
}
