package com.power.posval.domain.command;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Command to compute exchange fees for all executed contracts on a given delivery day.
 * The service loads gross traded volume from the position ledger and applies the
 * effective ExchangeFeeSchedule rates for the tenant and exchange.
 * Pattern #17, S4.6, DA-SET-03.
 */
public record ComputeExchangeFees(
    String tenantId,
    String exchange,
    LocalDate deliveryDay
) {
    public ComputeExchangeFees {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (exchange.isBlank()) {
            throw new IllegalArgumentException("exchange must not be blank");
        }
    }
}
