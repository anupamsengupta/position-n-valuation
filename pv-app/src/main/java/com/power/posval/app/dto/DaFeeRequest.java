package com.power.posval.app.dto;

import com.power.posval.domain.command.ComputeExchangeFees;

import java.time.LocalDate;

/**
 * REST request DTO for {@code POST /api/da/fees}.
 *
 * <p>Triggers exchange fee computation for a given exchange and delivery day.
 * Converted to a {@link ComputeExchangeFees} domain command.
 * Simulator-scope (pv-app). S9.4, DA-SET-03.
 */
public record DaFeeRequest(
    String tenantId,
    String exchange,
    String deliveryDay
) {

    /** Convert to the {@link ComputeExchangeFees} domain command. */
    public ComputeExchangeFees toCommand() {
        return new ComputeExchangeFees(
            tenantId,
            exchange,
            LocalDate.parse(deliveryDay)
        );
    }
}
