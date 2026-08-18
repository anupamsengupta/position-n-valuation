package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.TimeGranularity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pre-computed per-position aggregate at DAILY and MONTHLY granularities.
 *
 * <p>Contains settled actuals from S5a aggregated per FR-035 (TWA for MW,
 * sum for MWh, volume-weighted average for price, sum for amounts).
 * Forward marks are NOT materialized here per D-3 — the {@code hasForwardIntervals}
 * flag governs whether the query path calls {@code ForwardMarkService.computeMonthlyMark()}
 * at read time.
 *
 * <p>Not bitemporal: this is a derived materialization re-derivable from S5a + S1.
 * Re-computation on source change produces correct current state. See S7.5.
 *
 * <p>Pattern #3 (Value Object). S7, Pattern #18, FR-035, D-3, D-14.
 *
 * @param positionId          S1 position ledger entry ID
 * @param tenantId            tenant identifier (Pattern #32, D-14)
 * @param tradeId             trade identifier (denormalized from S1)
 * @param tradeLegId          trade leg identifier (denormalized from S1)
 * @param tradeVersion        trade version (denormalized from S1)
 * @param deliveryPointId     delivery point (denormalized from S1)
 * @param portfolioId         portfolio (denormalized from S1)
 * @param periodStart         rollup period start (UTC)
 * @param periodEnd           rollup period end (UTC)
 * @param granularity         DAILY or MONTHLY
 * @param settledMw           TWA of MW from S5a cells (FR-035, VOLUME domain)
 * @param settledMwh          sum of MWh from S5a cells (FR-035, ENERGY domain)
 * @param avgPrice            volume-weighted avg: settledValue / settledMwh (PRICE domain)
 * @param settledValue        sum of amount from S5a cells (MONETARY domain)
 * @param marketValue         sum of marketAmount from S5a cells (MONETARY domain)
 * @param realizedPnl         sum of pnl from S5a cells (MONETARY domain)
 * @param hasForwardIntervals whether S6b records exist beyond settlement horizon (D-3)
 * @param deliveryStatus      SETTLED / PARTIAL / FORWARD — derived from S5a and S6b presence
 * @param quantity            signed position quantity from S1 (VOLUME domain)
 * @param volumeUnit          volume unit name from S1
 * @param currency            currency code (from S5a or defaulted to EUR)
 * @param versionHash         content hash for staleness detection
 * @param refreshedAt         timestamp of last materialization
 */
public record TradeLegRollupCell(
        UUID positionId,
        String tenantId,
        String tradeId,
        String tradeLegId,
        int tradeVersion,
        String deliveryPointId,
        String portfolioId,
        Instant periodStart,
        Instant periodEnd,
        TimeGranularity granularity,
        BigDecimal settledMw,
        BigDecimal settledMwh,
        BigDecimal avgPrice,
        BigDecimal settledValue,
        BigDecimal marketValue,
        BigDecimal realizedPnl,
        boolean hasForwardIntervals,
        String deliveryStatus,
        BigDecimal quantity,
        String volumeUnit,
        String currency,
        String versionHash,
        Instant refreshedAt
) {}
