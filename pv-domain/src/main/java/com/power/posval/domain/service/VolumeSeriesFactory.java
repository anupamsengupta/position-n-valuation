package com.power.posval.domain.service;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Factory for creating VolumeSeries with correct ownership routing.
 * D-11: PROFILE (per-trade, multiplier=1.0) vs FORECAST (per-asset, shared).
 * Pattern #7, D-11, FR-050, S3.
 */
public class VolumeSeriesFactory {

    private final VolumeSeriesRepository seriesRepo;

    @Inject
    public VolumeSeriesFactory(VolumeSeriesRepository seriesRepo) {
        this.seriesRepo = seriesRepo;
    }

    /**
     * Create a PROFILE series for a trade-leg (DA/bilateral).
     * FR-050: per-trade, dedicated, multiplier=1.0.
     */
    public VolumeSeries createProfile(String tenantId,
                                       String tradeLegId,
                                       String tradeId,
                                       int tradeVersion,
                                       DeliveryPeriod deliveryPeriod,
                                       VolumeUnit volumeUnit,
                                       TimeGranularity granularity,
                                       List<VolumeInterval> intervals) {
        Objects.requireNonNull(tradeLegId, "tradeLegId required for PROFILE");

        String keyValue = "VS-" + tradeId + "-" + tradeVersion;
        var series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey(keyValue))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId(tradeLegId)
            .versionId(tradeVersion)
            .volumeUnit(volumeUnit)
            .granularity(granularity)
            .deliveryPeriod(deliveryPeriod)
            .qualityState(QualityState.CURRENT)
            .materializationStatus(MaterializationStatus.FULL)
            .totalExpectedIntervals(intervals.size())
            .materializedIntervalCount(intervals.size())
            .transactionTime(Instant.now())
            .intervals(intervals)
            .build();

        seriesRepo.save(series);
        return series;
    }

    /**
     * Create or locate existing FORECAST series for an asset.
     * FR-050: per-asset, shared — one series per (assetId, delivery window).
     */
    public VolumeSeries createOrGetForecast(String tenantId,
                                             String assetId,
                                             DeliveryPeriod deliveryPeriod,
                                             VolumeUnit volumeUnit,
                                             TimeGranularity granularity) {
        Objects.requireNonNull(assetId, "assetId required for FORECAST");

        String keyValue = "FCST-" + assetId;
        Optional<VolumeSeries> existing = seriesRepo.findCurrentBySeriesKey(tenantId, keyValue);
        if (existing.isPresent()) {
            return existing.get();
        }

        var series = DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey(keyValue))
            .seriesType(SeriesType.FORECAST)
            .assetId(assetId)
            .versionId(1L)
            .volumeUnit(volumeUnit)
            .granularity(granularity)
            .deliveryPeriod(deliveryPeriod)
            .qualityState(QualityState.CURRENT)
            .materializationStatus(MaterializationStatus.PENDING)
            .totalExpectedIntervals(0)
            .materializedIntervalCount(0)
            .transactionTime(Instant.now())
            .build();

        seriesRepo.save(series);
        return series;
    }
}
