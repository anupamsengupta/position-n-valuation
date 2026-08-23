package com.power.posval.app.dto;

import com.power.posval.domain.command.ComputeImbalanceSettlement;

import java.time.LocalDate;

/**
 * REST request DTO for {@code POST /api/da/imbalance}.
 *
 * <p>Triggers imbalance settlement computation for a balancing group and delivery day.
 * Converted to a {@link ComputeImbalanceSettlement} domain command.
 * Simulator-scope (pv-app). S9.4, DA-SET-04.
 */
public record DaImbalanceRequest(
    String tenantId,
    String balancingGroupId,
    String deliveryDay
) {

    /** Convert to the {@link ComputeImbalanceSettlement} domain command. */
    public ComputeImbalanceSettlement toCommand() {
        return new ComputeImbalanceSettlement(
            tenantId,
            balancingGroupId,
            LocalDate.parse(deliveryDay)
        );
    }
}
