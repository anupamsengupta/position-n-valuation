package com.power.posval.domain.port.service;

import com.power.posval.domain.command.ComputeExchangeFees;
import com.power.posval.domain.model.value.ExchangeFeeResult;

/**
 * Service port for exchange fee computation (DA-SET-03).
 *
 * <p>Fees are computed on gross (absolute) volume: {@code abs(quantityMw) * intervalHours}.
 * For each effective fee type (TRADING, CLEARING) a {@link FeeLineItem} is produced:
 * {@code feeAmount = grossVolumeMwh * ratePerMwh} at MONETARY scale (4 decimal places).
 *
 * <p>Fee rates are resolved from {@code ExchangeFeeScheduleRepository} using effective-date
 * lookup. The member tier defaults to the tenant's tier stored in the fee schedule table;
 * tier-specific rates override the default when present (S8.4).
 *
 * <p>Publishes {@code ExchangeFeesComputed} via the outbox on success.
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface ExchangeFeeService {

    /**
     * Compute exchange fees for all positions held on a given delivery day.
     *
     * @param command the fee computation command carrying tenantId, exchange,
     *                and deliveryDay; must not be null
     * @return the fee computation result carrying line items and total; never null
     */
    ExchangeFeeResult computeForDay(ComputeExchangeFees command);
}
