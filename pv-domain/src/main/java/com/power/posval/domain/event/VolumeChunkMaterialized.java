package com.power.posval.domain.event;

import com.power.posval.domain.model.MaterializationStatus;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;
import java.time.YearMonth;

/**
 * Emitted when a chunk of a PARTIAL series is materialized (rolling-horizon extension).
 * V3.0 §8.3. Pattern #14, FR-056, S3.
 */
public record VolumeChunkMaterialized(
    SeriesKey seriesKey,
    VolumeLayer layer,
    SeriesType seriesType,
    YearMonth chunkMonth,
    long versionId,
    int intervalCount,
    MaterializationStatus materializationStatus,
    Instant eventTime
) {}
