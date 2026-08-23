package com.power.posval.domain.port.service;

import com.power.posval.domain.port.repository.TARGET2CalendarRepository;

import java.util.Objects;

/**
 * Focused context record passed to {@link InstrumentCalculationStrategy#computePaymentDate}.
 * Carries only what a payment-date computation needs — no god-object coupling (S9b.3).
 *
 * <p>For DA exchange spot, the only required context is the TARGET2 calendar (D+2 convention).
 * Future instrument types (PPA, bilateral, broker-settled) may add contract-specific payment
 * terms here without modifying existing implementations (S9b.10).
 *
 * <p>Pattern #3, S9b.3.
 */
public record PaymentDateContext(
    TARGET2CalendarRepository target2Calendar
    // Future: PPA contract payment terms (NET30, NET60), broker settlement cycles
) {
    public PaymentDateContext {
        Objects.requireNonNull(target2Calendar, "target2Calendar");
    }
}
