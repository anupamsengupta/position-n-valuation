package com.power.posval.domain.port.service;

import com.power.posval.domain.model.value.FeeResult;
import com.power.posval.domain.model.value.PostSettlementResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Strategy interface for instrument-specific post-settlement calculations.
 *
 * <p>Encapsulates all instrument-type differences that cannot be encoded in price
 * expressions ({@code PriceExpression} sealed hierarchy, D-2) or volume references
 * ({@code VolumeReference}, D-11). These include fee computation, payment date rules,
 * P&amp;L reference price selection, forward valuation requirements, and counterparty
 * identification (S9b.1).
 *
 * <p>The strategy operates <em>after</em> core settlement cells have been materialised
 * by {@code SettlementMaterializationJob}. It does NOT touch {@code AbstractMaterializationJob}
 * (which is {@code final}) — it is invoked by a downstream consumer of the
 * {@code SettlementComputed} event (S9b.7, Pattern #15).
 *
 * <p><strong>Note on sealing:</strong> This interface is intentionally non-sealed
 * for v1 because sealed interfaces require all permitted subtypes to exist at compile
 * time. Once {@code IntraDayStrategy}, {@code PpaStrategy}, and {@code ForwardStrategy}
 * are implemented, this interface should be converted to a sealed interface with an
 * explicit {@code permits} clause. The Guice multibinder (S9b.6) is the authoritative
 * registry in the meantime — the compiler does not currently enforce exhaustive dispatch.
 * See tech spec S9b.2 for the target sealed design.
 *
 * <p>Implementations register themselves via Guice multibinder (S9.1, S9b.6):
 * <pre>
 * Multibinder&lt;InstrumentCalculationStrategy&gt; binder =
 *     Multibinder.newSetBinder(binder(), InstrumentCalculationStrategy.class);
 * binder.addBinding().to(DaExchangeSpotStrategy.class);
 * </pre>
 *
 * <p>Pattern #10 (Strategy) + Pattern #5 (future sealed hierarchy), S9b.2.
 * D-13 compliant — no Spring imports.
 */
public interface InstrumentCalculationStrategy {

    /**
     * Instrument type identifier used for strategy dispatch.
     * Matched against {@code instrumentType} on the position ledger entry (S9b.8).
     * Example values: {@code "DA_EXCHANGE_SPOT"}, {@code "INTRADAY_CONTINUOUS"},
     * {@code "PPA"}, {@code "FORWARD"}.
     *
     * @return a non-null, non-blank instrument type string
     */
    String instrumentType();

    /**
     * Post-settlement hook called after core settlement cells are materialized.
     * Returns {@link PostSettlementResult#noOp()} for instruments that require no
     * post-settlement adjustments (e.g. DA exchange spot — S9b.4).
     *
     * @param ctx context carrying settled cells and market data access; never null
     * @return the post-settlement result; never null
     */
    PostSettlementResult postSettle(PostSettlementContext ctx);

    /**
     * Compute exchange fees for a delivery day.
     * Returns {@link Optional#empty()} for instruments that have no exchange fees
     * (e.g. bilateral PPA contracts).
     *
     * @param ctx context carrying gross volume and fee schedule access; never null
     * @return the fee result if applicable, or empty if this instrument incurs no fees
     */
    Optional<FeeResult> computeFees(FeeContext ctx);

    /**
     * Compute the settlement payment date for a delivery day.
     * For DA exchange spot this is D+2 TARGET2 business days (DA-SET-02).
     *
     * @param deliveryDay the CET-interpreted delivery date
     * @param ctx         context carrying the TARGET2 calendar; never null
     * @return the payment date; never null
     */
    LocalDate computePaymentDate(LocalDate deliveryDay, PaymentDateContext ctx);

    /**
     * Return the P&amp;L reference price for a given delivery interval.
     * Returns {@link Optional#empty()} for self-referencing instruments where
     * P&amp;L = 0 against the instrument's own traded price (e.g. DA exchange spot — DA-VAL-01).
     *
     * @param intervalStart UTC interval start instant
     * @param ctx           context carrying market data access; never null
     * @return the external reference price in EUR/MWh, or empty if self-referencing
     */
    Optional<BigDecimal> referencePriceForInterval(Instant intervalStart, PnlContext ctx);

    /**
     * Return {@code true} if this instrument requires mark-to-market (forward/MtM)
     * valuation in addition to realized settlement.
     * DA exchange spot is fully realized — returns {@code false}.
     * Forward contracts and open PPA positions return {@code true}.
     *
     * @return {@code true} if forward valuation is required
     */
    boolean requiresForwardValuation();

    /**
     * Return the cashflow counterparty identifier for this instrument.
     * For DA exchange spot this is {@code "ECC"} (European Commodity Clearing — S9b.4).
     *
     * @param ctx context carrying tenant and exchange information; never null
     * @return the counterparty identifier string; never null
     */
    String counterpartyId(CounterpartyContext ctx);
}
