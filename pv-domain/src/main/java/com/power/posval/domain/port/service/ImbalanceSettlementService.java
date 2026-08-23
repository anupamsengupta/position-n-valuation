package com.power.posval.domain.port.service;

import com.power.posval.domain.command.ComputeImbalanceSettlement;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.value.ImbalanceMonthSummary;

import java.time.YearMonth;
import java.util.List;

/**
 * Service port for TSO imbalance settlement computation (DA-SET-04).
 *
 * <p>For each 15-min delivery interval on a given day, the service computes:
 * <pre>
 *   imbalanceVolumeMw  = actualDeliveredMw - nominatedVolumeMw
 *   imbalanceEnergyMwh = imbalanceVolumeMw * (intervalDurationHours)
 *   imbalanceAmount    = imbalanceEnergyMwh * reBAP (MONETARY scale 4, A-8)
 * </pre>
 *
 * <p>reBAP prices are loaded from {@code MarketDataPort} under the series key
 * {@code "REBAP_" + tsoArea}. Metered actuals are loaded from the existing
 * {@code MeteredActualRepository} or via {@code MarketDataPort} (S8.3).
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface ImbalanceSettlementService {

    /**
     * Compute and persist imbalance records for all delivery intervals on a given day
     * for a given balancing group.
     *
     * <p>Publishes {@code ImbalanceSettlementComputed} via the outbox on success (S8.3).
     *
     * @param command the computation command carrying tenantId, balancingGroupId,
     *                and deliveryDay; must not be null
     * @return the computed and persisted imbalance records ordered by interval start;
     *         never null; may be empty if no intervals have data
     */
    List<ImbalanceRecord> computeForDay(ComputeImbalanceSettlement command);

    /**
     * Aggregate all imbalance records for a balancing group across a calendar month.
     * Returns net MWh (algebraic sum) and net amount (algebraic sum at MONETARY scale 4)
     * plus a per-day breakdown suitable for reconciliation.
     *
     * @param tenantId         tenant identifier (D-14, Pattern #32)
     * @param balancingGroupId balancing group identifier
     * @param month            the calendar month to aggregate
     * @return the monthly imbalance summary; never null
     */
    ImbalanceMonthSummary monthlyAggregate(String tenantId, String balancingGroupId,
                                            YearMonth month);
}
