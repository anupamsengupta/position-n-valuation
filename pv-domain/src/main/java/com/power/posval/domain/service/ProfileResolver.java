package com.power.posval.domain.service;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.util.List;

/**
 * Resolves volume for PROFILE series (per-trade, multiplier=1.0).
 * FR-051a: PROFILE is the designed path for DA/bilateral settlement —
 * not a fallback. meteredSeriesKey is null by design.
 * Pattern #9, D-11.
 */
public record ProfileResolver(
    VolumeSeriesRepository seriesRepo
) implements VolumeResolver {

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       DeliveryRange intervalRange,
                                       ResolutionPurpose purpose) {
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

    private static boolean overlaps(java.time.Instant start, java.time.Instant end,
                                     DeliveryRange range) {
        return start.isBefore(range.endInstant().toInstant())
            && end.isAfter(range.startInstant().toInstant());
    }
}
