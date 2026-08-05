package com.power.posval.domain.service;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
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
 * Checks {@link VolumeCache} first; on miss, delegates to the wrapped resolver,
 * then populates the cache with raw (pre-multiplier) interval data.
 *
 * <p>Cache key: ({tenantId}, {seriesKey}, {intervalStart}).
 * Cache stores raw MW/MWh from the volume series (before multiplier).
 * Multiplier is applied after cache retrieval.
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
        // Use tradeId as tenantId (same normalization as ProfileResolver)
        String tenantId = ref.tradeId();

        // Build list of expected 15-min interval starts in [rangeStart, rangeEnd)
        List<Instant> expectedStarts = buildIntervalStarts(rangeStart, rangeEnd);
        if (expectedStarts.isEmpty()) {
            return List.of();
        }

        // Bulk cache lookup
        List<CachedInterval> cached = cache.getAll(tenantId, seriesKey, expectedStarts);

        if (cached.size() == expectedStarts.size()) {
            // Full cache hit — apply multiplier and return
            return applyMultiplier(cached, ref.multiplier(), purpose);
        }

        // Cache miss (partial or full) — delegate to underlying resolver
        List<VolumeRecord> resolved = delegate.resolve(ref, rangeStart, rangeEnd, purpose);

        // Populate cache with raw (pre-multiplier) intervals from DB
        populateCache(tenantId, seriesKey, ref, rangeStart, rangeEnd);

        return resolved;
    }

    private void populateCache(String tenantId, String seriesKey,
                                VolumeReference ref,
                                Instant rangeStart, Instant rangeEnd) {
        try {
            Optional<VolumeSeries> seriesOpt = seriesRepo.findCurrentBySeriesKeyAndRange(
                    tenantId, seriesKey, rangeStart, rangeEnd);
            if (seriesOpt.isPresent()) {
                Map<Instant, CachedInterval> entries = new HashMap<>();
                for (VolumeInterval vi : seriesOpt.get().intervals()) {
                    if (!vi.intervalStart().isBefore(rangeEnd)) break;
                    if (vi.intervalEnd().isAfter(rangeStart)) {
                        entries.put(vi.intervalStart(), new CachedInterval(
                                vi.intervalStart(), vi.intervalEnd(),
                                vi.volume(), vi.energy(),
                                false, String.valueOf(seriesOpt.get().versionId()), "v1"));
                    }
                }
                if (!entries.isEmpty()) {
                    cache.putAll(tenantId, seriesKey, entries);
                }
            }
        } catch (Exception e) {
            // Cache population failure should not break resolution
        }
    }

    private List<VolumeRecord> applyMultiplier(List<CachedInterval> cached,
                                                BigDecimal multiplier,
                                                ResolutionPurpose purpose) {
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
                    Long.parseLong(ci.calendarVersion()),
                    QualityState.CURRENT,
                    SeriesType.FORECAST,
                    null, multiplier));
        }
        return result;
    }

    private List<Instant> buildIntervalStarts(Instant rangeStart, Instant rangeEnd) {
        var starts = new ArrayList<Instant>();
        Instant cursor = rangeStart;
        Duration step = Duration.ofMinutes(15);
        while (cursor.isBefore(rangeEnd)) {
            starts.add(cursor);
            cursor = cursor.plus(step);
        }
        return starts;
    }
}
