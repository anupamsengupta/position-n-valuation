package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.marketdata.MarketDataPort;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Template method for materialization jobs.
 * Concrete subclasses: SettlementMaterializationJob, ForwardMarkJob, EodStrikeJob.
 * Pattern #15, FR-056, FR-105, S5.
 */
public abstract class AbstractMaterializationJob {

    protected final VolumeResolver volumeResolver;
    protected final PriceEvaluator priceEvaluator;
    protected final MarketDataPort marketData;

    protected AbstractMaterializationJob(VolumeResolver volumeResolver,
                                          PriceEvaluator priceEvaluator,
                                          MarketDataPort marketData) {
        this.volumeResolver = volumeResolver;
        this.priceEvaluator = priceEvaluator;
        this.marketData = marketData;
    }

    /**
     * Orchestration skeleton — not overridable.
     * FR-105: restartable and idempotent.
     */
    public final void execute(PositionLedgerEntry position,
                               DeliveryRange intervalRange) {
        List<VolumeRecord> volumes = resolveVolume(position, intervalRange);

        for (VolumeRecord vol : volumes) {
            DeliveryPeriod interval = new DeliveryPeriod(
                ZonedDateTime.ofInstant(vol.intervalStart(),
                    intervalRange.deliveryTimezone()),
                ZonedDateTime.ofInstant(vol.intervalEnd(),
                    intervalRange.deliveryTimezone()),
                intervalRange.deliveryTimezone());

            PriceResolution priceRes = evaluatePrice(
                position.priceExpressionId(), interval);

            writeResult(position, vol, priceRes);
        }
    }

    /** Hook: resolve volume from the appropriate source. */
    protected abstract List<VolumeRecord> resolveVolume(
        PositionLedgerEntry position, DeliveryRange intervalRange);

    /** Hook: evaluate the price expression tree. */
    protected abstract PriceResolution evaluatePrice(
        UUID priceExpressionId, DeliveryPeriod interval);

    /** Hook: write the materialized result. */
    protected abstract void writeResult(
        PositionLedgerEntry position, VolumeRecord volume,
        PriceResolution price);
}
