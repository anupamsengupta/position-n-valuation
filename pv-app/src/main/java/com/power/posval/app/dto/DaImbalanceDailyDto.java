package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTO for {@code GET /api/da/imbalance/daily} — daily imbalance detail grid.
 *
 * <p>Contains per-interval imbalance rows and daily summary totals. Matches
 * {@code DaImbalanceDaily} in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-04.
 */
public record DaImbalanceDailyDto(
    LocalDate deliveryDay,
    String balancingGroupId,
    List<Row> rows,
    BigDecimal netImbalanceMwh,
    BigDecimal netImbalanceAmount,
    String currency
) {

    /**
     * Per-interval imbalance row.
     *
     * <p>All MW/MWh/amount fields are signed: positive = long imbalance, negative = short.
     */
    public record Row(
        UUID recordId,
        Instant intervalStart,
        Instant intervalEnd,
        BigDecimal nominatedVolumeMw,
        BigDecimal actualDeliveredMw,
        BigDecimal imbalanceVolumeMw,
        BigDecimal imbalanceEnergyMwh,
        BigDecimal imbalancePricePerMwh,
        BigDecimal imbalanceAmount,
        int recordVersion
    ) {}
}
