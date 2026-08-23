package com.power.posval.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Emitted by ImbalanceSettlementService when all interval imbalance records
 * for a delivery day have been computed and persisted.
 * {@code netImbalanceMwh} and {@code netImbalanceAmount} are day-level aggregates.
 * Pattern #14, S4.5, DA-SET-04.
 */
public record ImbalanceSettlementComputed(
    String tenantId,
    String balancingGroupId,
    LocalDate deliveryDay,
    BigDecimal netImbalanceMwh,
    BigDecimal netImbalanceAmount,
    Instant eventTime
) {}
