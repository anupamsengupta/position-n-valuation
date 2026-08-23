package com.power.posval.domain.port.service;

import java.util.Objects;

/**
 * Focused context record passed to
 * {@link InstrumentCalculationStrategy#counterpartyId}.
 * Carries only what a counterparty identification needs — no god-object coupling (S9b.3).
 *
 * <p>For DA exchange spot the counterparty is always {@code "ECC"} (European Commodity
 * Clearing) and the context carries the exchange name for future look-ups.
 * Future instrument types (PPA, bilateral) will add counterparty leg information here
 * without modifying existing strategy implementations (S9b.10).
 *
 * <p>Pattern #3, S9b.3.
 */
public record CounterpartyContext(
    String tenantId,
    String exchange
    // Future: PPA counterparty entity, broker identity, LEI
) {
    public CounterpartyContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(exchange, "exchange");
    }
}
