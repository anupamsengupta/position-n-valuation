package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO for {@code GET /api/da/kpi} — KPI summary for a delivery day.
 *
 * <p>Aggregates key metrics across settlement, fees, imbalance, and alerts for the
 * requested delivery day and bidding zone. Matches {@code DaKpiSummary} in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-07.
 */
public record DaKpiDto(
    LocalDate deliveryDay,
    String zone,
    BigDecimal netVolumeMwh,
    BigDecimal vwap,               // null if no energy traded
    BigDecimal settlementTotal,
    BigDecimal exchangeFees,
    BigDecimal imbalanceCost,      // null if not computed
    long openAlertCount,
    String maxAlertSeverity,       // "CRITICAL", "WARNING", "INFO", or null if no open alerts
    String currency
) {}
