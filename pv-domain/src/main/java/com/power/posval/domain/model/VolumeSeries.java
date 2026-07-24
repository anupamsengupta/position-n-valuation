package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;
import java.time.YearMonth;
import java.util.SequencedSet;
import java.util.UUID;

/**
 * Unified volume series — FORECAST (per asset, shared) or PROFILE (per trade-leg, dedicated).
 * Consumers resolve volume identically: interval.volume × reference.multiplier.
 * Pattern #2, FR-050, D-11, S3, V3.0 §3.3.1.
 */
public interface VolumeSeries {

    UUID id();
    SeriesKey seriesKey();
    SeriesType seriesType();
    String assetId();          // non-null for FORECAST
    String tradeLegId();       // non-null for PROFILE
    long versionId();
    VolumeUnit volumeUnit();
    TimeGranularity granularity();
    DeliveryPeriod deliveryPeriod();
    QualityState qualityState();
    MaterializationStatus materializationStatus();
    YearMonth materializedThrough();
    int totalExpectedIntervals();
    int materializedIntervalCount();
    Instant transactionTime();
    Instant validTime();       // set for PROFILE (REMIT, FR-009g)

    /** Intervals ordered by intervalStart. */
    SequencedSet<VolumeInterval> intervals();
}
