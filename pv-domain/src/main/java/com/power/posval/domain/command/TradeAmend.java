package com.power.posval.domain.command;

import com.power.posval.domain.model.value.DeliveryPeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Amends an existing trade. Creates new ledger versions with valid-time
 * or knowledge-time adjustment per FR-008.
 * Pattern #17, FR-001–FR-005, S1.
 */
public record TradeAmend(
    String tradeId,
    int tradeVersion,
    String tradeLegId,
    String tenantId,
    String amendmentReason,    // BACKDATED_CORRECTION or FORWARD_EFFECTIVE
    Instant businessEffectiveDate,
    // Fields that changed — nullable means "unchanged"
    BigDecimal quantity,
    UUID priceExpressionId,
    String portfolioId,
    BigDecimal multiplier,
    DeliveryPeriod deliveryPeriod
) {}
