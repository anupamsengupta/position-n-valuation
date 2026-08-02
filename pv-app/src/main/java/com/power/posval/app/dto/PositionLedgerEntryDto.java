package com.power.posval.app.dto;

import com.power.posval.domain.model.PositionLedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionLedgerEntryDto(
        String id,
        String tenantId,
        String tradeId,
        String tradeLegId,
        int tradeVersion,
        String deliveryStartMonth,
        String deliveryEndMonth,
        BigDecimal quantity,
        String volumeUnit,
        String priceExpressionId,
        String portfolioId,
        String deliveryPointId,
        String status,
        Instant validFrom,
        Instant validTo,
        Instant knownFrom,
        Instant knownTo
) {
    public static PositionLedgerEntryDto from(PositionLedgerEntry e) {
        return new PositionLedgerEntryDto(
                e.id().toString(),
                e.tenantId(),
                e.tradeId(),
                e.tradeLegId(),
                e.tradeVersion(),
                e.deliveryRange().startMonth().toString(),
                e.deliveryRange().endMonth().toString(),
                e.quantity(),
                e.volumeUnit().name(),
                e.priceExpressionId().toString(),
                e.portfolioId(),
                e.deliveryPointId(),
                e.status(),
                e.validFrom(),
                e.validTo(),
                e.knownFrom(),
                e.knownTo()
        );
    }
}
