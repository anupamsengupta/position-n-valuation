package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Template method for materialization jobs.
 * Concrete subclasses: SettlementMaterializationJob, ForwardMarkJob, EodStrikeJob.
 * Pattern #15, FR-056, FR-105, S5.
 *
 * Market data lookups are instrumented via {@link InstrumentedMarketDataPort}
 * to separate lookup time (cache + DB) from computation time (expression eval).
 * Timing stats are thread-local so concurrent Kafka consumer threads don't
 * contaminate each other's measurements.
 */
public abstract class AbstractMaterializationJob<R> {

    private static final Logger log = LoggerFactory.getLogger(AbstractMaterializationJob.class);

    protected final VolumeResolver volumeResolver;
    protected final PriceEvaluator priceEvaluator;
    protected final MarketDataPort marketData;
    protected final PriceExpressionRepository priceExpressionRepo;

    private final InstrumentedMarketDataPort instrumentedMarketData;

    protected AbstractMaterializationJob(VolumeResolver volumeResolver,
                                          PriceEvaluator priceEvaluator,
                                          MarketDataPort marketData,
                                          PriceExpressionRepository priceExpressionRepo) {
        this.volumeResolver = volumeResolver;
        this.priceEvaluator = priceEvaluator;
        this.priceExpressionRepo = priceExpressionRepo;

        // Wrap market data port with timing instrumentation (thread-local stats)
        this.instrumentedMarketData = new InstrumentedMarketDataPort(marketData);
        this.marketData = this.instrumentedMarketData;
    }

    /**
     * Orchestration skeleton — not overridable.
     * FR-105: restartable and idempotent.
     * Collect-then-flush: builds results in memory, then batch-flushes.
     */
    public final void execute(PositionLedgerEntry position,
                               DeliveryRange intervalRange) {
        long start = System.currentTimeMillis();
        List<VolumeRecord> volumes = resolveVolume(position, intervalRange);
        long resolveMs = System.currentTimeMillis() - start;
        log.info("resolveVolume(): {} ms, intervals={}", resolveMs, volumes.size());

        instrumentedMarketData.resetStats();
        try {
            start = System.currentTimeMillis();
            List<R> results = new ArrayList<>(volumes.size());
            for (VolumeRecord vol : volumes) {
                DeliveryPeriod interval = new DeliveryPeriod(
                    ZonedDateTime.ofInstant(vol.intervalStart(),
                        intervalRange.deliveryTimezone()),
                    ZonedDateTime.ofInstant(vol.intervalEnd(),
                        intervalRange.deliveryTimezone()),
                    intervalRange.deliveryTimezone());

                PriceResolution priceRes = evaluatePrice(
                    position.priceExpressionId(), interval);

                results.add(buildResult(position, vol, priceRes));
            }
            long priceMs = System.currentTimeMillis() - start;
            PriceCalcTimingStats stats = instrumentedMarketData.stats();
            long lookupMs = stats.lookupTotalMs();
            long computeMs = priceMs - lookupMs;
            log.info("priceCalc(): {} ms (lookup={} ms, compute={} ms, lookups={}), results={}",
                priceMs, lookupMs, computeMs, stats.lookupCount(), results.size());

            start = System.currentTimeMillis();
            flushResults(position, results);
            long flushMs = System.currentTimeMillis() - start;
            log.info("flushResults(): {} ms", flushMs);
        } finally {
            instrumentedMarketData.clearStats();
        }
    }

    /** Hook: resolve volume from the appropriate source. */
    protected abstract List<VolumeRecord> resolveVolume(
        PositionLedgerEntry position, DeliveryRange intervalRange);

    /** Hook: evaluate the price expression tree. */
    protected abstract PriceResolution evaluatePrice(
        UUID priceExpressionId, DeliveryPeriod interval);

    /** Hook: build a single result (pure computation, no I/O). */
    protected abstract R buildResult(
        PositionLedgerEntry position, VolumeRecord volume,
        PriceResolution price);

    /** Hook: flush all results in batch (persist + publish). */
    protected abstract void flushResults(
        PositionLedgerEntry position, List<R> results);
}
