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
 */
public abstract class AbstractMaterializationJob<R> {

    private static final Logger log = LoggerFactory.getLogger(AbstractMaterializationJob.class);

    protected final VolumeResolver volumeResolver;
    protected final PriceEvaluator priceEvaluator;
    protected final MarketDataPort marketData;
    protected final PriceExpressionRepository priceExpressionRepo;

    protected AbstractMaterializationJob(VolumeResolver volumeResolver,
                                          PriceEvaluator priceEvaluator,
                                          MarketDataPort marketData,
                                          PriceExpressionRepository priceExpressionRepo) {
        this.volumeResolver = volumeResolver;
        this.priceEvaluator = priceEvaluator;
        this.marketData = marketData;
        this.priceExpressionRepo = priceExpressionRepo;
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
        log.info("priceCalc(): {} ms, results={}", priceMs, results.size());

        start = System.currentTimeMillis();
        flushResults(position, results);
        long flushMs = System.currentTimeMillis() - start;
        log.info("flushResults(): {} ms", flushMs);
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
