package com.power.posval.domain.event;

import com.power.posval.domain.model.MaterializationStatus;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.VolumeLayer;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;

/**
 * Emitted when a new volume series version is created (first publication or re-materialization).
 * V3.0 §8.1. Pattern #14, #27, FR-052c, S3.
 */
public record VolumePublished(
    SeriesKey seriesKey,
    VolumeLayer layer,
    SeriesType seriesType,     // nullable for METERED_ACTUAL
    long versionId,
    DeliveryPeriod deliveryRange,
    TimeGranularity granularity,
    QualityState qualityState,
    String scope,              // "FULL" or "PARTIAL"
    Instant eventTime
) {}
