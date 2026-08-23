package com.power.posval.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Emitted by ExchangeFeeService when exchange fees for a delivery day have
 * been computed and are ready for cashflow generation.
 * Pattern #14, S4.5, DA-SET-03.
 */
public record ExchangeFeesComputed(
    String tenantId,
    String exchange,
    LocalDate deliveryDay,
    BigDecimal totalFeeAmount,
    String currency,
    Instant eventTime
) {}
