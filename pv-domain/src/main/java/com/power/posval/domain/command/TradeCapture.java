package com.power.posval.domain.command;

import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Captures a new trade position. Creates PositionLedgerEntry blocks
 * and VolumeReference(s) for volume resolution.
 * Pattern #17, FR-001–FR-005, S1.
 */
public record TradeCapture(
    String tradeId,
    int tradeVersion,
    String tradeLegId,
    String tenantId,
    DeliveryPeriod deliveryPeriod,
    BigDecimal quantity,
    VolumeUnit volumeUnit,
    UUID priceExpressionId,
    String portfolioId,
    String deliveryPointId,
    String originType,         // EXCHANGE_FILL, BILATERAL_TRADE, ...
    Instant businessEffectiveDate,
    // Volume reference fields
    String assetId,            // nullable — set for PPA
    BigDecimal multiplier,
    SeriesKey volumeSeriesKey,
    SeriesKey meteredSeriesKey  // nullable
) {}
