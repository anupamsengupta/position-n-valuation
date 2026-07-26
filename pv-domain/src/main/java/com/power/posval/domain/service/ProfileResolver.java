package com.power.posval.domain.service;

import com.power.posval.domain.model.MeteredActualInterval;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.VolumeInterval;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedSet;

/**
 * Resolves volume for PROFILE series (per-trade, multiplier=1.0).
 * FR-051a: PROFILE is the designed path for DA/bilateral settlement —
 * not a fallback. meteredSeriesKey is null by design.
 * Pattern #9, D-11.
 *
 * <h3>Performance optimizations</h3>
 * <ul>
 *   <li>Range boundaries resolved to epoch-millis <b>once</b> per call,
 *       not per-interval (eliminates 2 × N {@code ZonedDateTime.toInstant()} calls)</li>
 *   <li>Per-interval overlap: two primitive {@code long} comparisons instead of
 *       {@code Instant.isBefore/isAfter} (no object allocation)</li>
 *   <li>Sorted-order early exit: intervals are ordered by {@code intervalStart},
 *       so iteration breaks at the first interval past the range end</li>
 *   <li>Multiplier=1.0 fast path: skips {@code BigDecimal.multiply()} when
 *       the multiplier is unity (PROFILE trades)</li>
 * </ul>
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
        return filterAndMap(series.intervals(), intervalRange, ref.multiplier(),
            series.versionId(), series.qualityState(), series.seriesType());
    }

    /**
     * Filters intervals overlapping the delivery range and applies the multiplier.
     * Shared with {@link ForecastResolver} to avoid duplication.
     */
    static List<VolumeRecord> filterAndMap(SequencedSet<VolumeInterval> intervals,
                                            DeliveryRange range,
                                            BigDecimal multiplier,
                                            long versionId,
                                            QualityState quality,
                                            SeriesType seriesType) {
        return filterAndMap(intervals, range, multiplier,
            versionId, quality, seriesType, null);
    }

    /**
     * Core filter+map with optional metering point ID.
     */
    static List<VolumeRecord> filterAndMap(SequencedSet<VolumeInterval> intervals,
                                            DeliveryRange range,
                                            BigDecimal multiplier,
                                            long versionId,
                                            QualityState quality,
                                            SeriesType seriesType,
                                            String meteringPointId) {
        // Resolve boundaries ONCE — not per-interval
        long rangeStartMillis = range.startInstant().toInstant().toEpochMilli();
        long rangeEndMillis = range.endInstant().toInstant().toEpochMilli();

        var result = new ArrayList<VolumeRecord>();
        boolean unitMultiplier = BigDecimal.ONE.compareTo(multiplier) == 0;

        for (VolumeInterval i : intervals) {
            long iStartMillis = i.intervalStart().toEpochMilli();
            long iEndMillis = i.intervalEnd().toEpochMilli();

            // Sorted order: if this interval starts at or past range end, done
            if (iStartMillis >= rangeEndMillis) break;

            // Skip intervals ending before or at range start
            if (iEndMillis <= rangeStartMillis) continue;

            // Overlaps — apply multiplier (skip multiply for the common 1.0 case)
            BigDecimal vol = unitMultiplier ? i.volume() : i.volume().multiply(multiplier);
            BigDecimal energy = unitMultiplier ? i.energy() : i.energy().multiply(multiplier);

            result.add(new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                vol, energy,
                versionId, quality,
                seriesType, meteringPointId, multiplier));
        }
        return result;
    }

    /**
     * Overload for {@link MeteredActualInterval} (same optimization, different interface).
     */
    static List<VolumeRecord> filterAndMapMetered(SequencedSet<MeteredActualInterval> intervals,
                                                   DeliveryRange range,
                                                   BigDecimal multiplier,
                                                   long versionId,
                                                   QualityState quality,
                                                   String meteringPointId) {
        long rangeStartMillis = range.startInstant().toInstant().toEpochMilli();
        long rangeEndMillis = range.endInstant().toInstant().toEpochMilli();

        var result = new ArrayList<VolumeRecord>();
        boolean unitMultiplier = BigDecimal.ONE.compareTo(multiplier) == 0;

        for (MeteredActualInterval i : intervals) {
            long iStartMillis = i.intervalStart().toEpochMilli();
            long iEndMillis = i.intervalEnd().toEpochMilli();

            if (iStartMillis >= rangeEndMillis) break;
            if (iEndMillis <= rangeStartMillis) continue;

            BigDecimal vol = unitMultiplier ? i.volume() : i.volume().multiply(multiplier);
            BigDecimal energy = unitMultiplier ? i.energy() : i.energy().multiply(multiplier);

            result.add(new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                vol, energy,
                versionId, quality,
                null, meteringPointId, multiplier));
        }
        return result;
    }
}
