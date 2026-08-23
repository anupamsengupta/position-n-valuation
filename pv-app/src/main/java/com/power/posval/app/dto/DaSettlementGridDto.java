package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for {@code GET /api/da/settlement} — settlement grid for a delivery day.
 *
 * <p>Contains interval-level rows plus a summary (total energy, total settlement,
 * volume-weighted average price). Matches the Zod schema {@code DaSettlementGrid}
 * defined in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-02.
 */
public record DaSettlementGridDto(
    LocalDate deliveryDay,
    String zone,
    List<DaSettlementRowDto> rows,
    BigDecimal totalEnergyMwh,
    BigDecimal totalSettlementAmount,
    BigDecimal vwap,         // volume-weighted average price; null if no energy
    String currency
) {}
