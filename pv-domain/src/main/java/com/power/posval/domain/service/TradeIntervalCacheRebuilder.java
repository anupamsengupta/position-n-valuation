package com.power.posval.domain.service;

import com.power.posval.domain.exception.MaterializationException;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import jakarta.inject.Inject;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * S6b rebuild logic. Uses virtual threads for parallel chunk processing.
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
     * Uses virtual threads for I/O-bound parallel chunk processing.
     */
    public void rebuildForTradeLeg(String tenantId,
                                    VolumeReference ref,
                                    DeliveryRange fullRange) {
        List<YearMonth> months = toMonths(fullRange);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = months.stream()
                .map(month -> executor.submit(() -> {
                    DeliveryRange monthRange = DeliveryRange.ofMonth(
                        month, fullRange.deliveryTimezone());
                    List<VolumeRecord> volumes = resolver.resolve(
                        ref, monthRange, ResolutionPurpose.FORWARD);
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
                }))
                .toList();

            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new MaterializationException(
                "S6b rebuild failed for trade-leg " + ref.tradeLegId(), e);
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
