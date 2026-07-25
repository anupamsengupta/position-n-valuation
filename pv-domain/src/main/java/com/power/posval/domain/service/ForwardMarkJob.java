package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.ForwardMarkStore;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                           PriceExpressionRepository priceExpressionRepo,
                           ForwardMarkStore markStore) {
        super(volumeResolver, priceEvaluator, marketData, priceExpressionRepo);
        this.markStore = markStore;
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        VolumeReference ref = buildVolumeReference(position);
        return volumeResolver.resolve(ref, intervalRange, ResolutionPurpose.FORWARD);
    }

    @Override
    protected PriceResolution evaluatePrice(UUID priceExpressionId,
                                             DeliveryPeriod interval) {
        var exprOpt = priceExpressionRepo.findById(priceExpressionId);
        if (exprOpt.isEmpty()) {
            return new PriceResolution(BigDecimal.ZERO, Set.of(), Map.of());
        }
        return priceEvaluator.evaluate(exprOpt.get(), interval,
            ResolutionPurpose.FORWARD, marketData);
    }

    @Override
    protected void writeResult(PositionLedgerEntry position,
                                VolumeRecord volume,
                                PriceResolution price) {
        BigDecimal markValue = price.value().multiply(volume.energy());
        markStore.put(
            position.tenantId(),
            position.id(),
            volume.intervalStart(),
            volume.intervalEnd(),
            markValue,
            "EUR",
            price.inputVersionSet());
    }

    private VolumeReference buildVolumeReference(PositionLedgerEntry position) {
        return VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId(position.tradeLegId())
            .tradeId(position.tradeId())
            .multiplier(BigDecimal.ONE)
            .volumeSeriesKey(position.volumeSeriesKey())
            .effectiveFrom(ZonedDateTime.ofInstant(
                position.validFrom(), position.deliveryRange().deliveryTimezone()))
            .effectiveTo(ZonedDateTime.ofInstant(
                position.deliveryRange().endInstant().toInstant(),
                position.deliveryRange().deliveryTimezone()))
            .build();
    }
}
