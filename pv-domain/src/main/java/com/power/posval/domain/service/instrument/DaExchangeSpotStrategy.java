package com.power.posval.domain.service.instrument;

import com.power.posval.domain.model.value.FeeLineItem;
import com.power.posval.domain.model.value.FeeResult;
import com.power.posval.domain.model.value.PostSettlementResult;
import com.power.posval.domain.model.ExchangeFeeSchedule;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.service.CounterpartyContext;
import com.power.posval.domain.port.service.FeeContext;
import com.power.posval.domain.port.service.InstrumentCalculationStrategy;
import com.power.posval.domain.port.service.PaymentDateContext;
import com.power.posval.domain.port.service.PnlContext;
import com.power.posval.domain.port.service.PostSettlementContext;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Instrument calculation strategy for DA exchange spot trades.
 *
 * <p>This implementation encapsulates all DA-specific post-settlement behaviour:
 * <ul>
 *   <li>{@link #postSettle}: no-op — all settlement is computed by the core
 *       {@code SettlementMaterializationJob} pipeline (Pattern #15, D-13).</li>
 *   <li>{@link #computeFees}: TRADING + CLEARING fees on gross MWh using the effective
 *       {@code ExchangeFeeSchedule} for EPEX SPOT (DA-SET-03, S9b.4).</li>
 *   <li>{@link #computePaymentDate}: D+2 TARGET2 business days (DA-SET-02).</li>
 *   <li>{@link #referencePriceForInterval}: returns empty — DA self-references mean P&amp;L = 0
 *       against own clearing price (DA-VAL-01).</li>
 *   <li>{@link #requiresForwardValuation}: {@code false} — DA is fully realized.</li>
 *   <li>{@link #counterpartyId}: {@code "ECC"} (European Commodity Clearing, S9b.4).</li>
 * </ul>
 *
 * <p>Registered in the Guice multibinder via {@code DaExchangeModule}:
 * <pre>
 * strategyBinder.addBinding().to(DaExchangeSpotStrategy.class);
 * </pre>
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * D-2 compliant: does not touch price expression internals — operates post-settlement.
 * Pattern #10 (Strategy), S9b.4.
 */
public final class DaExchangeSpotStrategy implements InstrumentCalculationStrategy {

    /** Instrument type identifier for strategy dispatch (S9b.8). */
    public static final String INSTRUMENT_TYPE = "DA_EXCHANGE_SPOT";

    /** Fee types processed for EPEX DA exchange spot (DA-SET-03, S8.4). */
    private static final List<String> FEE_TYPES = List.of("TRADING", "CLEARING");

    /** Currency for all EPEX DA fees. */
    private static final String EUR = "EUR";

    private final NumericPrecision numericPrecision;

    @Inject
    public DaExchangeSpotStrategy(NumericPrecision numericPrecision) {
        this.numericPrecision = Objects.requireNonNull(numericPrecision, "numericPrecision");
    }

    /**
     * Instrument type identifier for strategy dispatch.
     * Matched against {@code instrumentType} on the position ledger entry (S9b.8).
     *
     * @return {@code "DA_EXCHANGE_SPOT"}
     */
    @Override
    public String instrumentType() {
        return INSTRUMENT_TYPE;
    }

    /**
     * DA exchange spot has no post-settlement adjustments — all settlement values
     * are computed in the core {@code SettlementMaterializationJob} pipeline (S9b.4).
     *
     * @param ctx post-settlement context; never null
     * @return {@link PostSettlementResult#noOp()}; never null
     */
    @Override
    public PostSettlementResult postSettle(PostSettlementContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return PostSettlementResult.noOp();
    }

    /**
     * Compute EPEX DA exchange fees (TRADING + CLEARING) for a delivery day (DA-SET-03).
     *
     * <p>Algorithm:
     * <pre>
     *   for feeType in [TRADING, CLEARING]:
     *     schedule = feeScheduleRepo.findEffective(tenantId, exchange, feeType, deliveryDay)
     *     if present:
     *       feeAmount = grossVolumeMwh * ratePerMwh  [rounded MONETARY]
     *       -> FeeLineItem
     * </pre>
     *
     * <p>Fee types with no configured schedule are silently skipped.
     *
     * @param ctx fee computation context; never null
     * @return the fee result; present if at least one fee schedule is configured,
     *         empty if no schedules found for this tenant/exchange/day
     */
    @Override
    public Optional<FeeResult> computeFees(FeeContext ctx) {
        Objects.requireNonNull(ctx, "ctx");

        List<FeeLineItem> lineItems = new ArrayList<>();
        for (String feeType : FEE_TYPES) {
            Optional<ExchangeFeeSchedule> scheduleOpt =
                ctx.feeScheduleRepo().findEffective(
                    ctx.tenantId(), ctx.exchange(), feeType, ctx.deliveryDay());
            if (scheduleOpt.isEmpty()) {
                continue;
            }
            ExchangeFeeSchedule schedule = scheduleOpt.get();
            BigDecimal feeAmount = numericPrecision.round(
                ctx.grossVolumeMwh().multiply(schedule.ratePerMwh()),
                NumericPrecision.Domain.MONETARY
            );
            lineItems.add(new FeeLineItem(
                feeType,
                schedule.ratePerMwh(),
                ctx.grossVolumeMwh(),
                feeAmount
            ));
        }

        if (lineItems.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal totalFeeAmount = lineItems.stream()
            .map(FeeLineItem::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalFeeAmount = numericPrecision.round(totalFeeAmount, NumericPrecision.Domain.MONETARY);

        return Optional.of(new FeeResult(lineItems, totalFeeAmount, EUR));
    }

    /**
     * Compute the settlement payment date as D+2 TARGET2 business days (DA-SET-02).
     *
     * @param deliveryDay the CET-interpreted delivery date; never null
     * @param ctx         context carrying the TARGET2 calendar; never null
     * @return the payment date; never null
     */
    @Override
    public LocalDate computePaymentDate(LocalDate deliveryDay, PaymentDateContext ctx) {
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        Objects.requireNonNull(ctx,         "ctx");
        return ctx.target2Calendar().nthBusinessDayAfter(deliveryDay, 2);
    }

    /**
     * DA exchange spot self-references: P&amp;L = 0 against own clearing price (DA-VAL-01).
     * Returns {@link Optional#empty()} to signal no external reference price is needed.
     *
     * @param intervalStart UTC interval start; never null
     * @param ctx           P&amp;L context; never null
     * @return empty; DA positions carry zero P&amp;L against their own benchmark
     */
    @Override
    public Optional<BigDecimal> referencePriceForInterval(Instant intervalStart, PnlContext ctx) {
        Objects.requireNonNull(intervalStart, "intervalStart");
        Objects.requireNonNull(ctx,           "ctx");
        return Optional.empty();
    }

    /**
     * DA exchange spot is fully realized — no mark-to-market valuation is required.
     *
     * @return {@code false}
     */
    @Override
    public boolean requiresForwardValuation() {
        return false;
    }

    /**
     * Cashflow counterparty for DA exchange spot is ECC (European Commodity Clearing, S9b.4).
     *
     * @param ctx counterparty context; never null
     * @return {@code "ECC"}; never null
     */
    @Override
    public String counterpartyId(CounterpartyContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return "ECC";
    }
}
