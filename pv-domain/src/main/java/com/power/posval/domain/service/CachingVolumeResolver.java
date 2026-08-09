package com.power.posval.domain.service;

import com.power.posval.domain.model.VolumeInterval;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.CachedInterval;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Cache-through decorator for {@link VolumeResolver}.
 * Checks {@link VolumeCache} first; on miss, queries DB once,
 * populates cache, applies multiplier, and returns — no double DB query.
 *
 * <p>Cache key: ({tenantId}, {seriesKey}, {intervalStart}).
 * Cache stores raw MW/MWh from the volume series (before multiplier).
 * Multiplier is applied after cache retrieval.
 *
 * <p>Flow:
 * <ol>
 *   <li>Query series metadata from DB (lightweight, no intervals) to get granularity</li>
 *   <li>Build expected interval starts from granularity</li>
 *   <li>Bulk cache lookup — if full hit, apply multiplier and return (no interval DB query)</li>
 *   <li>On miss: load intervals from DB, populate cache, apply multiplier, return</li>
 * </ol>
 *
 * <p>Pattern #29 (read-through), FR-079.
 */
public final class CachingVolumeResolver implements VolumeResolver {

    private final VolumeResolver delegate;
    private final VolumeCache cache;
    private final VolumeSeriesRepository seriesRepo;
    private final NumericPrecision np;

    public CachingVolumeResolver(VolumeResolver delegate,
                                  VolumeCache cache,
                                  VolumeSeriesRepository seriesRepo,
                                  NumericPrecision np) {
        this.delegate = delegate;
        this.cache = cache;
        this.seriesRepo = seriesRepo;
        this.np = np;
    }

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       Instant rangeStart,
                                       Instant rangeEnd,
                                       ResolutionPurpose purpose) {
        String seriesKey = ref.volumeSeriesKey().value();
        String tenantId = ref.tenantId();

        // Load series with intervals in range (single DB query)
        Optional<VolumeSeries> seriesOpt = seriesRepo.findCurrentBySeriesKeyAndRange(
                tenantId, seriesKey, rangeStart, rangeEnd);
        if (seriesOpt.isEmpty()) {
            return List.of();
        }

        VolumeSeries series = seriesOpt.get();
        Duration step = series.granularity().getFixedDuration();

        // Build expected interval starts based on actual series granularity
        List<Instant> expectedStarts = buildIntervalStarts(rangeStart, rangeEnd, step);
        if (expectedStarts.isEmpty()) {
            return List.of();
        }

        // Bulk cache lookup
        List<CachedInterval> cached = cache.getAll(tenantId, seriesKey, expectedStarts);

        if (cached.size() == expectedStarts.size()) {
            // Full cache hit — apply multiplier and return (no extra DB query)
            return applyMultiplier(cached, ref.multiplier(),
                    series.versionId(), series.qualityState(), series.seriesType());
        }

        // Cache miss — populate cache from the already-loaded series intervals
        Map<Instant, CachedInterval> entries = new HashMap<>();
        for (VolumeInterval vi : series.intervals()) {
            if (!vi.intervalStart().isBefore(rangeEnd)) break;
            if (vi.intervalEnd().isAfter(rangeStart)) {
                entries.put(vi.intervalStart(), new CachedInterval(
                        vi.intervalStart(), vi.intervalEnd(),
                        vi.volume(), vi.energy(),
                        false, String.valueOf(series.versionId()), "v1"));
            }
        }
        if (!entries.isEmpty()) {
            try {
                cache.putAll(tenantId, seriesKey, entries);
            } catch (Exception ignored) {
                // Cache population failure should not break resolution
            }
        }

        // Apply multiplier to the already-loaded intervals (no second DB query)
        return VolumeFilterMapper.filterAndMap(
                series.intervals(), rangeStart, rangeEnd, ref.multiplier(),
                series.versionId(), series.qualityState(), series.seriesType(), null, np);
    }

    private List<VolumeRecord> applyMultiplier(List<CachedInterval> cached,
                                                BigDecimal multiplier,
                                                long versionId,
                                                com.power.posval.domain.model.QualityState quality,
                                                com.power.posval.domain.model.SeriesType seriesType) {
        boolean unitMultiplier = BigDecimal.ONE.compareTo(multiplier) == 0;
        var result = new ArrayList<VolumeRecord>(cached.size());
        for (CachedInterval ci : cached) {
            BigDecimal vol;
            BigDecimal energy;
            if (unitMultiplier) {
                vol = ci.netMw();
                energy = ci.netMwh();
            } else {
                vol = np.round(ci.netMw().multiply(multiplier), NumericPrecision.Domain.VOLUME);
                energy = np.round(ci.netMwh().multiply(multiplier), NumericPrecision.Domain.ENERGY);
            }
            result.add(new VolumeRecord(
                    ci.intervalStart(), ci.intervalEnd(),
                    vol, energy,
                    versionId, quality,
                    seriesType, null, multiplier));
        }
        return result;
    }

    private List<Instant> buildIntervalStarts(Instant rangeStart, Instant rangeEnd, Duration step) {
        var starts = new ArrayList<Instant>();
        Instant cursor = rangeStart;
        while (cursor.isBefore(rangeEnd)) {
            starts.add(cursor);
            cursor = cursor.plus(step);
        }
        return starts;
    }
}
