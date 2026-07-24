package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;
import java.time.YearMonth;
import java.util.SequencedSet;
import java.util.UUID;

/**
 * Per-asset metered actual volume series. TSO-sourced, append-only.
 * Pattern #2, #34, S3, V3.0 §3.3.4.
 */
public interface MeteredActualVolumeSeries {

    UUID id();
    SeriesKey seriesKey();
    String assetId();              // always non-null
    String meteringPointId();      // EIC-W reference
    long versionId();
    VolumeUnit volumeUnit();
    TimeGranularity granularity();
    DeliveryPeriod deliveryPeriod();
    QualityState qualityState();   // restricted to PROVISIONAL, VALIDATED, ESTIMATED
    MaterializationStatus materializationStatus();
    YearMonth materializedThrough();
    int totalExpectedIntervals();
    int materializedIntervalCount();
    Instant transactionTime();
    Instant receivedAt();          // when TSO data arrived

    SequencedSet<MeteredActualInterval> intervals();
}
