package com.power.posval.app.dto;

import com.power.posval.domain.command.TradeAmend;
import com.power.posval.domain.model.value.DeliveryPeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

public record TradeAmendRequest(
        String tradeId,
        int tradeVersion,
        String tradeLegId,
        String tenantId,
        String amendmentReason,
        String businessEffectiveDate,
        String quantity,
        String priceExpressionId,
        String marketPriceExpressionId,
        String portfolioId,
        String multiplier,
        String deliveryStart,
        String deliveryEnd,
        String deliveryTimezone
) {
    public TradeAmend toCommand() {
        DeliveryPeriod dp = null;
        if (deliveryStart != null && deliveryEnd != null && deliveryTimezone != null) {
            ZoneId tz = ZoneId.of(deliveryTimezone);
            dp = DeliveryPeriod.of(
                    ZonedDateTime.parse(deliveryStart).withZoneSameInstant(tz),
                    ZonedDateTime.parse(deliveryEnd).withZoneSameInstant(tz),
                    tz);
        }
        return new TradeAmend(
                tradeId,
                tradeVersion,
                tradeLegId,
                tenantId,
                amendmentReason,
                Instant.parse(businessEffectiveDate),
                quantity != null ? new BigDecimal(quantity) : null,
                priceExpressionId != null ? UUID.fromString(priceExpressionId) : null,
                marketPriceExpressionId != null ? UUID.fromString(marketPriceExpressionId) : null,
                portfolioId,
                multiplier != null ? new BigDecimal(multiplier) : null,
                dp
        );
    }
}
