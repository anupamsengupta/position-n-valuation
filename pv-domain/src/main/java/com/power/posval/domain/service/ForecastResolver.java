package com.power.posval.domain.service;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.repository.MeteredActualRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.util.List;

/**
 * Resolves volume for FORECAST series (per-asset, shared, multiplier < 1.0).
 * FR-051a: SETTLEMENT purpose reads meteredSeriesKey if set;
 *          FORWARD purpose reads volumeSeriesKey (forecast).
 * Pattern #9, D-11.
 */
public record ForecastResolver(
    VolumeSeriesRepository seriesRepo,
    MeteredActualRepository meteredRepo
) implements VolumeResolver {

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       DeliveryRange intervalRange,
                                       ResolutionPurpose purpose) {
        if (purpose == ResolutionPurpose.SETTLEMENT
                && ref.meteredSeriesKey() != null) {
            return resolveFromMetered(ref, intervalRange);
        }
        return resolveFromForecast(ref, intervalRange);
    }

    private List<VolumeRecord> resolveFromForecast(VolumeReference ref,
                                                    DeliveryRange intervalRange) {
        var seriesOpt = seriesRepo.findCurrentBySeriesKey(
            ref.tradeId(), ref.volumeSeriesKey().value());
        if (seriesOpt.isEmpty()) {
            return List.of();
        }
        var series = seriesOpt.get();
        return series.intervals().stream()
            .filter(i -> overlaps(i.intervalStart(), i.intervalEnd(), intervalRange))
            .map(i -> new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                i.volume().multiply(ref.multiplier()),
                i.energy().multiply(ref.multiplier()),
                series.versionId(), series.qualityState(),
                series.seriesType(), null, ref.multiplier()))
            .toList();
    }

    private List<VolumeRecord> resolveFromMetered(VolumeReference ref,
                                                   DeliveryRange intervalRange) {
        var seriesOpt = meteredRepo.findCurrentBySeriesKey(
            ref.tradeId(), ref.meteredSeriesKey());
        if (seriesOpt.isEmpty()) {
            return List.of();
        }
        var series = seriesOpt.get();
        return series.intervals().stream()
            .filter(i -> overlaps(i.intervalStart(), i.intervalEnd(), intervalRange))
            .map(i -> new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                i.volume().multiply(ref.multiplier()),
                i.energy().multiply(ref.multiplier()),
                series.versionId(), series.qualityState(),
                null, series.meteringPointId(), ref.multiplier()))
            .toList();
    }

    private static boolean overlaps(java.time.Instant start, java.time.Instant end,
                                     DeliveryRange range) {
        return start.isBefore(range.endInstant().toInstant())
            && end.isAfter(range.startInstant().toInstant());
    }
}
