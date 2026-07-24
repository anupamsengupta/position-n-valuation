package com.power.posval.domain.event;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;

/**
 * Primary revaluation trigger. Emitted when a volume series version is superseded.
 * event_time becomes known_from on resulting valuation-cell versions.
 * V3.0 §8.2, FR-052a. Pattern #14, #27, S3/S8.
 */
public record VolumeSuperseded(
    SeriesKey seriesKey,
    VolumeLayer layer,
    SeriesType seriesType,     // nullable for METERED_ACTUAL
    DeliveryPeriod affectedRange,
    Long oldVersionId,         // nullable for first publication
    long newVersionId,
    QualityState qualityState,
    Instant eventTime
) {}
