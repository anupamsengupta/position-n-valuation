package com.power.posval.app.dto;

import com.power.posval.domain.command.TradeCancel;

import java.time.Instant;

public record TradeCancelRequest(
        String tradeId,
        int tradeVersion,
        String tradeLegId,
        String tenantId,
        String cancellationType,
        String businessEffectiveDate
) {
    public TradeCancel toCommand() {
        return new TradeCancel(
                tradeId,
                tradeVersion,
                tradeLegId,
                tenantId,
                cancellationType,
                Instant.parse(businessEffectiveDate)
        );
    }
}
