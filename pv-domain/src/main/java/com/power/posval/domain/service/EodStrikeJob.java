package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.marketdata.MarketDataPort;

import java.util.List;
import java.util.UUID;

/**
 * EOD struck mark materialization job (S5c).
 * Persists immutable struck mark.
 * Pattern #15, FR-077, FR-078, S5c.
 */
public class EodStrikeJob extends AbstractMaterializationJob {

    public EodStrikeJob(VolumeResolver volumeResolver,
                         PriceEvaluator priceEvaluator,
                         MarketDataPort marketData) {
        super(volumeResolver, priceEvaluator, marketData);
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        return List.of();
    }

    @Override
    protected PriceResolution evaluatePrice(UUID priceExpressionId,
                                             DeliveryPeriod interval) {
        return null;
    }

    @Override
    protected void writeResult(PositionLedgerEntry position,
                                VolumeRecord volume,
                                PriceResolution price) {
        // Skeleton — persists immutable struck mark via StruckMarkRepository
    }
}
