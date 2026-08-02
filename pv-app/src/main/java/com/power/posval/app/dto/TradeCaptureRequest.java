package com.power.posval.app.dto;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

public record TradeCaptureRequest(
        String tradeId,
        int tradeVersion,
        String tradeLegId,
        String tenantId,
        String deliveryStart,
        String deliveryEnd,
        String deliveryTimezone,
        String quantity,
        String volumeUnit,
        String priceExpressionId,
        String portfolioId,
        String deliveryPointId,
        String originType,
        String businessEffectiveDate,
        String assetId,
        String multiplier,
        String volumeSeriesKey,
        String meteredSeriesKey
) {
    public TradeCapture toCommand() {
        ZoneId tz = ZoneId.of(deliveryTimezone);
        return new TradeCapture(
                tradeId,
                tradeVersion,
                tradeLegId,
                tenantId,
                DeliveryPeriod.of(
                        ZonedDateTime.parse(deliveryStart).withZoneSameInstant(tz),
                        ZonedDateTime.parse(deliveryEnd).withZoneSameInstant(tz),
                        tz),
                new BigDecimal(quantity),
                VolumeUnit.valueOf(volumeUnit),
                UUID.fromString(priceExpressionId),
                portfolioId,
                deliveryPointId,
                originType,
                Instant.parse(businessEffectiveDate),
                assetId,
                multiplier != null ? new BigDecimal(multiplier) : BigDecimal.ONE,
                volumeSeriesKey != null ? new SeriesKey(volumeSeriesKey) : null,
                meteredSeriesKey != null ? new SeriesKey(meteredSeriesKey) : null
        );
    }
}
