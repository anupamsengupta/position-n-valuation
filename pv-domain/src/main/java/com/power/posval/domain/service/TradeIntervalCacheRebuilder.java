package com.power.posval.domain.service;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import jakarta.inject.Inject;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * S6b rebuild logic. Processes month chunks sequentially within the caller's transaction.
 * FR-086b, D-12, S6b.
 */
public class TradeIntervalCacheRebuilder {

    private final TradeIntervalCache cache;
    private final VolumeResolver resolver;
    private final VolumeSeriesRepository seriesRepo;

    @Inject
    public TradeIntervalCacheRebuilder(TradeIntervalCache cache,
                                        VolumeResolver resolver,
                                        VolumeSeriesRepository seriesRepo) {
        this.cache = cache;
        this.resolver = resolver;
        this.seriesRepo = seriesRepo;
    }

    /**
     * Rebuild S6b for a trade-leg within exact delivery boundaries.
     * Month-chunking keeps batch sizes manageable; each chunk is clamped
     * to [deliveryStart, deliveryEnd) so sub-month trades only populate
     * their actual intervals.
     *
     * @param deliveryStart exact delivery start (may be mid-month)
     * @param deliveryEnd   exact delivery end (may be mid-month)
     * @param monthRange    month-aligned range for purge and chunking
     */
    public void rebuildForTradeLeg(String tenantId,
                                    VolumeReference ref,
                                    Instant deliveryStart,
                                    Instant deliveryEnd,
                                    DeliveryRange monthRange) {
        List<YearMonth> months = toMonths(monthRange);

        // Purge only the exact delivery interval, not the full month
        cache.rebuild(tenantId, ref.tradeLegId(), deliveryStart, deliveryEnd);

        for (YearMonth month : months) {
            DeliveryRange chunk = DeliveryRange.ofMonth(
                month, monthRange.deliveryTimezone());
            // Clamp to actual delivery boundaries — a 1-day trade in July
            // should not resolve all of July's intervals
            Instant chunkStart = max(chunk.startInstant().toInstant(), deliveryStart);
            Instant chunkEnd = min(chunk.endInstant().toInstant(), deliveryEnd);
            if (!chunkStart.isBefore(chunkEnd)) continue;

            List<VolumeRecord> volumes = resolver.resolve(
                ref, chunkStart, chunkEnd, ResolutionPurpose.FORWARD);
            List<TradeIntervalRecord> records = volumes.stream()
                .map(v -> new TradeIntervalRecord(
                    ref.tradeLegId(),
                    v.intervalStart(), v.intervalEnd(),
                    v.volume(), v.energy(),
                    ref.multiplier(),
                    ref.volumeSeriesKey().value(),
                    String.valueOf(v.versionId())))
                .toList();
            cache.writeAll(tenantId, records);
        }
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    private static List<YearMonth> toMonths(DeliveryRange range) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth ym = range.startMonth();
        while (!ym.isAfter(range.endMonth())) {
            months.add(ym);
            ym = ym.plusMonths(1);
        }
        return months;
    }
}
