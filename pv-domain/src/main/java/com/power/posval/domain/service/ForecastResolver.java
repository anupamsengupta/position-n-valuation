package com.power.posval.domain.service;

import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.repository.MeteredActualRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.time.Instant;
import java.util.List;

/**
 * Resolves volume for FORECAST series (per-asset, shared, multiplier &lt; 1.0).
 * FR-051a: SETTLEMENT purpose reads meteredSeriesKey if set;
 *          FORWARD purpose reads volumeSeriesKey (forecast).
 * Pattern #9, D-11.
 *
 * <p>Delegates filtering and mapping to {@link VolumeFilterMapper}
 * (shared with {@link ProfileResolver}).
 */
public record ForecastResolver(
    VolumeSeriesRepository seriesRepo,
    MeteredActualRepository meteredRepo,
    NumericPrecision np
) implements VolumeResolver {

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       Instant rangeStart,
                                       Instant rangeEnd,
                                       ResolutionPurpose purpose) {
        if (purpose == ResolutionPurpose.SETTLEMENT
                && ref.meteredSeriesKey() != null) {
            return resolveFromMetered(ref, rangeStart, rangeEnd);
        }
        return resolveFromForecast(ref, rangeStart, rangeEnd);
    }

    private List<VolumeRecord> resolveFromForecast(VolumeReference ref,
                                                    Instant rangeStart,
                                                    Instant rangeEnd) {
        var seriesOpt = seriesRepo.findCurrentBySeriesKeyAndRange(
            ref.tradeId(), ref.volumeSeriesKey().value(),
            rangeStart, rangeEnd);
        if (seriesOpt.isEmpty()) {
            return List.of();
        }
        var series = seriesOpt.get();
        return VolumeFilterMapper.filterAndMap(series.intervals(), rangeStart, rangeEnd,
            ref.multiplier(), series.versionId(), series.qualityState(),
            series.seriesType(), null, np);
    }

    private List<VolumeRecord> resolveFromMetered(VolumeReference ref,
                                                   Instant rangeStart,
                                                   Instant rangeEnd) {
        var seriesOpt = meteredRepo.findCurrentBySeriesKey(
            ref.tradeId(), ref.meteredSeriesKey());
        if (seriesOpt.isEmpty()) {
            return List.of();
        }
        var series = seriesOpt.get();
        return VolumeFilterMapper.filterAndMapMetered(series.intervals(), rangeStart, rangeEnd,
            ref.multiplier(), series.versionId(), series.qualityState(),
            series.meteringPointId(), np);
    }
}
