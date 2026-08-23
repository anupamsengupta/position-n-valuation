package com.power.posval.domain.port.service;

import com.power.posval.domain.port.repository.ExchangeFeeScheduleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Focused context record passed to {@link InstrumentCalculationStrategy#computeFees}.
 * Carries only what a fee computation needs — no god-object coupling (S9b.3).
 *
 * <p>Pattern #3, S9b.3.
 */
public record FeeContext(
    String tenantId,
    String exchange,
    LocalDate deliveryDay,
    BigDecimal grossVolumeMwh,
    ExchangeFeeScheduleRepository feeScheduleRepo
) {
    public FeeContext {
        Objects.requireNonNull(tenantId,       "tenantId");
        Objects.requireNonNull(exchange,       "exchange");
        Objects.requireNonNull(deliveryDay,    "deliveryDay");
        Objects.requireNonNull(grossVolumeMwh, "grossVolumeMwh");
        Objects.requireNonNull(feeScheduleRepo, "feeScheduleRepo");
    }
}
