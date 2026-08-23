package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for {@code GET /api/da/fees} — fee breakdown for a delivery day.
 *
 * <p>Contains per-fee-type line items and totals. Matches {@code DaFeeBreakdown}
 * in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-05.
 */
public record DaFeeBreakdownDto(
    LocalDate deliveryDay,
    BigDecimal grossVolumeMwh,
    List<LineItemDto> items,
    BigDecimal totalFeeAmount,
    String currency
) {

    /** Single fee type line item. */
    public record LineItemDto(
        String feeType,
        BigDecimal ratePerMwh,
        BigDecimal volumeMwh,
        BigDecimal amount
    ) {}
}
