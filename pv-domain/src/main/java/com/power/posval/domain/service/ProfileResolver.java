package com.power.posval.domain.service;

import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.time.Instant;
import java.util.List;

/**
 * Resolves volume for PROFILE series (per-trade, multiplier=1.0).
 * FR-051a: PROFILE is the designed path for DA/bilateral settlement —
 * not a fallback. meteredSeriesKey is null by design.
 * Pattern #9, D-11.
 *
 * <p>Delegates filtering and mapping to {@link VolumeFilterMapper}
 * (shared with {@link ForecastResolver}).
 */
public record ProfileResolver(
    VolumeSeriesRepository seriesRepo,
    NumericPrecision np
) implements VolumeResolver {

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       Instant rangeStart,
                                       Instant rangeEnd,
                                       ResolutionPurpose purpose) {
        var seriesOpt = seriesRepo.findCurrentBySeriesKeyAndRange(
            ref.tradeId(), ref.volumeSeriesKey().value(),
            rangeStart, rangeEnd);
        if (seriesOpt.isEmpty()) {
            return List.of();
        }
        var series = seriesOpt.get();
        return VolumeFilterMapper.filterAndMap(
            series.intervals(), rangeStart, rangeEnd, ref.multiplier(),
            series.versionId(), series.qualityState(), series.seriesType(), null, np);
    }
}
