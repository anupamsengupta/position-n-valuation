package com.power.posval.domain.service;

import com.power.posval.domain.model.MeteredActualInterval;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.VolumeInterval;
import com.power.posval.domain.port.NumericPrecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedSet;

/**
 * Shared filter+map logic for volume resolution.
 * Used by both {@link ProfileResolver} and {@link ForecastResolver}
 * to avoid code duplication across the sealed {@link VolumeResolver} hierarchy.
 *
 * <p>Package-private — not part of the public API. The two resolvers are the
 * only intended callers.
 *
 * <h3>Optimizations applied</h3>
 * <ul>
 *   <li>Range boundaries resolved to epoch-millis once per call</li>
 *   <li>Per-interval overlap: primitive {@code long} comparison</li>
 *   <li>Sorted-order early exit at first interval past range end</li>
 *   <li>Multiplier=1.0 fast path skips {@code BigDecimal.multiply()}</li>
 *   <li>Domain-specific rounding via {@link NumericPrecision} (FR-036)</li>
 * </ul>
 */
final class VolumeFilterMapper {

    private VolumeFilterMapper() {} // utility class

    /**
     * Filters {@link VolumeInterval}s overlapping [rangeStart, rangeEnd),
     * applies multiplier with domain rounding, and maps to {@link VolumeRecord}s.
     */
    static List<VolumeRecord> filterAndMap(SequencedSet<VolumeInterval> intervals,
                                            Instant rangeStart,
                                            Instant rangeEnd,
                                            BigDecimal multiplier,
                                            long versionId,
                                            QualityState quality,
                                            SeriesType seriesType,
                                            String meteringPointId,
                                            NumericPrecision np) {
        long rangeStartMillis = rangeStart.toEpochMilli();
        long rangeEndMillis = rangeEnd.toEpochMilli();

        var result = new ArrayList<VolumeRecord>();
        boolean unitMultiplier = BigDecimal.ONE.compareTo(multiplier) == 0;

        for (VolumeInterval i : intervals) {
            long iStartMillis = i.intervalStart().toEpochMilli();
            long iEndMillis = i.intervalEnd().toEpochMilli();

            if (iStartMillis >= rangeEndMillis) break;
            if (iEndMillis <= rangeStartMillis) continue;

            BigDecimal vol;
            BigDecimal energy;
            if (unitMultiplier) {
                vol = i.volume();
                energy = i.energy();
            } else {
                vol = np.round(i.volume().multiply(multiplier), NumericPrecision.Domain.VOLUME);
                energy = np.round(i.energy().multiply(multiplier), NumericPrecision.Domain.ENERGY);
            }

            result.add(new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                vol, energy,
                versionId, quality,
                seriesType, meteringPointId, multiplier));
        }
        return result;
    }

    /**
     * Same as above for {@link MeteredActualInterval} (separate interface, same fields).
     */
    static List<VolumeRecord> filterAndMapMetered(SequencedSet<MeteredActualInterval> intervals,
                                                   Instant rangeStart,
                                                   Instant rangeEnd,
                                                   BigDecimal multiplier,
                                                   long versionId,
                                                   QualityState quality,
                                                   String meteringPointId,
                                                   NumericPrecision np) {
        long rangeStartMillis = rangeStart.toEpochMilli();
        long rangeEndMillis = rangeEnd.toEpochMilli();

        var result = new ArrayList<VolumeRecord>();
        boolean unitMultiplier = BigDecimal.ONE.compareTo(multiplier) == 0;

        for (MeteredActualInterval i : intervals) {
            long iStartMillis = i.intervalStart().toEpochMilli();
            long iEndMillis = i.intervalEnd().toEpochMilli();

            if (iStartMillis >= rangeEndMillis) break;
            if (iEndMillis <= rangeStartMillis) continue;

            BigDecimal vol;
            BigDecimal energy;
            if (unitMultiplier) {
                vol = i.volume();
                energy = i.energy();
            } else {
                vol = np.round(i.volume().multiply(multiplier), NumericPrecision.Domain.VOLUME);
                energy = np.round(i.energy().multiply(multiplier), NumericPrecision.Domain.ENERGY);
            }

            result.add(new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                vol, energy,
                versionId, quality,
                null, meteringPointId, multiplier));
        }
        return result;
    }
}
