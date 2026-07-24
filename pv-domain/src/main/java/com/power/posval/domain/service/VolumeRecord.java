package com.power.posval.domain.service;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resolved volume for a single interval after multiplier application.
 * D-11: trade_volume = volume_interval.volume × reference.multiplier.
 * Pattern #9, FR-051, S3.
 */
public record VolumeRecord(
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal volume,
    BigDecimal energy,
    long versionId,
    QualityState qualityState,
    SeriesType seriesType,
    String meteringPointId,
    BigDecimal multiplier
) {}
