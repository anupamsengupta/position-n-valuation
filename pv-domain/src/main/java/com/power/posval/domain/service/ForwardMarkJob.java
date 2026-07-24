package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.ForwardMarkStore;
import com.power.posval.domain.port.marketdata.MarketDataPort;

import java.util.List;
import java.util.UUID;

/**
 * Forward mark materialization job (S5b).
 * Purpose=FORWARD, overwrites ephemeral mark via ForwardMarkStore.
 * Pattern #15, FR-075, S5b.
 */
public class ForwardMarkJob extends AbstractMaterializationJob {

    private final ForwardMarkStore markStore;

    public ForwardMarkJob(VolumeResolver volumeResolver,
                           PriceEvaluator priceEvaluator,
                           MarketDataPort marketData,
                           ForwardMarkStore markStore) {
        super(volumeResolver, priceEvaluator, marketData);
        this.markStore = markStore;
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        // FR-051a: FORWARD reads forecast × multiplier
        return List.of();
    }

    @Override
    protected PriceResolution evaluatePrice(UUID priceExpressionId,
                                             DeliveryPeriod interval) {
        // FR-048e: purpose=FORWARD uses forward series
        return null;
    }

    @Override
    protected void writeResult(PositionLedgerEntry position,
                                VolumeRecord volume,
                                PriceResolution price) {
        // FR-075: overwrite ephemeral mark
        markStore.put(
            position.tenantId(),
            position.id(),
            volume.intervalStart(),
            volume.intervalEnd(),
            price.value(),
            "EUR",
            price.inputVersionSet());
    }
}
