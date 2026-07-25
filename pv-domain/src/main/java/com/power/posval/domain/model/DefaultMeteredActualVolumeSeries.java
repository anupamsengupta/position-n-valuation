package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

/**
 * Concrete record implementing MeteredActualVolumeSeries.
 * Pattern #2, #34, S3.
 */
public record DefaultMeteredActualVolumeSeries(
    UUID id,
    SeriesKey seriesKey,
    String assetId,
    String meteringPointId,
    long versionId,
    VolumeUnit volumeUnit,
    TimeGranularity granularity,
    DeliveryPeriod deliveryPeriod,
    QualityState qualityState,
    MaterializationStatus materializationStatus,
    YearMonth materializedThrough,
    int totalExpectedIntervals,
    int materializedIntervalCount,
    Instant transactionTime,
    Instant receivedAt,
    SequencedSet<MeteredActualInterval> intervals
) implements MeteredActualVolumeSeries {

    public DefaultMeteredActualVolumeSeries {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(seriesKey, "seriesKey");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(volumeUnit, "volumeUnit");
        Objects.requireNonNull(granularity, "granularity");
        Objects.requireNonNull(deliveryPeriod, "deliveryPeriod");
        Objects.requireNonNull(qualityState, "qualityState");
        Objects.requireNonNull(transactionTime, "transactionTime");
        intervals = Collections.unmodifiableSequencedSet(new TreeSet<>(intervals));
    }
}
