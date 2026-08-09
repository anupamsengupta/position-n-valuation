package com.power.posval.domain.service;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import jakarta.inject.Inject;
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
     * Rebuild S6b for a trade-leg across all delivery months.
     * Runs sequentially on the caller's thread to share the transaction-bound
     * EntityManager. Virtual threads cannot be used here because JPA's
     * EntityManager is thread-local / transaction-scoped.
     */
    public void rebuildForTradeLeg(String tenantId,
                                    VolumeReference ref,
                                    DeliveryRange fullRange) {
        List<YearMonth> months = toMonths(fullRange);

        // Purge stale entries before rebuilding to prevent duplicate accumulation
        cache.rebuild(tenantId, ref.tradeLegId(), fullRange);

        for (YearMonth month : months) {
            DeliveryRange monthRange = DeliveryRange.ofMonth(
                month, fullRange.deliveryTimezone());
            List<VolumeRecord> volumes = resolver.resolve(
                ref,
                monthRange.startInstant().toInstant(),
                monthRange.endInstant().toInstant(),
                ResolutionPurpose.FORWARD);
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
