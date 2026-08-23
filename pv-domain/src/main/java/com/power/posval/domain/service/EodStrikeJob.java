package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.StruckMark;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.StruckMarkRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * EOD struck mark materialization job (S5c).
 * Persists immutable struck mark.
 * Pattern #15, FR-077, FR-078, S5c.
 * TODO: Wire EodStrikeJob with NumericPrecision in Guice when binding is created.
 */
public class EodStrikeJob extends AbstractMaterializationJob<StruckMark> {

    private final StruckMarkRepository markRepo;
    private final LocalDate strikeDate;
    private final NumericPrecision np;

    public EodStrikeJob(VolumeResolver volumeResolver,
                         PriceEvaluator priceEvaluator,
                         MarketDataPort marketData,
                         PriceExpressionRepository priceExpressionRepo,
                         StruckMarkRepository markRepo,
                         LocalDate strikeDate,
                         NumericPrecision np) {
        super(volumeResolver, priceEvaluator, marketData, priceExpressionRepo);
        this.markRepo = markRepo;
        this.strikeDate = strikeDate;
        this.np = np;
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        VolumeReference ref = buildVolumeReference(position);
        return volumeResolver.resolve(ref,
            position.deliveryStart(), position.deliveryEnd(),
            ResolutionPurpose.FORWARD);
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
    protected StruckMark buildResult(PositionLedgerEntry position,
                                      VolumeRecord volume,
                                      PriceResolution price) {
        // OQ-1, S14: sign energy using quantity signum — struck marks must reflect trade direction.
        // OI-7: apply NumericPrecision.MONETARY rounding (pre-existing gap fixed here). FR-034, FR-077.
        int directionSign = position.quantity().signum();
        BigDecimal signedEnergy = volume.energy().multiply(BigDecimal.valueOf(directionSign));
        BigDecimal markValue = np.round(
            price.value().multiply(signedEnergy), NumericPrecision.Domain.MONETARY);
        YearMonth deliveryMonth = YearMonth.from(
            volume.intervalStart().atZone(position.deliveryRange().deliveryTimezone()));

        return new StruckMark(
            position.tenantId(),
            position.id(),
            deliveryMonth,
            strikeDate,
            markValue,
            "EUR",
            price.inputVersionSet(),
            null,
            Map.of(),
            0L,
            null,
            false,
            Instant.now());
    }

    @Override
    protected void flushResults(PositionLedgerEntry position, List<StruckMark> marks) {
        markRepo.saveAll(marks);
    }

    private VolumeReference buildVolumeReference(PositionLedgerEntry position) {
        return VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId(position.tradeLegId())
            .tradeId(position.tradeId())
            .tenantId(position.tenantId())
            .multiplier(position.multiplier())
            .volumeSeriesKey(position.volumeSeriesKey())
            .effectiveFrom(ZonedDateTime.ofInstant(
                position.validFrom(), position.deliveryRange().deliveryTimezone()))
            .effectiveTo(ZonedDateTime.ofInstant(
                position.deliveryRange().endInstant().toInstant(),
                position.deliveryRange().deliveryTimezone()))
            .build();
    }
}
