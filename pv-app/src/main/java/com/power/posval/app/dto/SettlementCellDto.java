package com.power.posval.app.dto;

import com.power.posval.domain.model.SettlementCell;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record SettlementCellDto(
        String cellId,
        String tenantId,
        String positionId,
        Instant intervalStart,
        Instant intervalEnd,
        String valuationType,
        String cellStatus,
        BigDecimal price,
        BigDecimal volumeMw,
        BigDecimal volumeMwh,
        BigDecimal amount,
        String currency,
        Set<String> activeLeaves,
        Instant validFrom,
        Instant validTo,
        Instant knownFrom,
        Instant knownTo
) {
    public static SettlementCellDto from(SettlementCell c) {
        return new SettlementCellDto(
                c.cellId().toString(),
                c.tenantId(),
                c.positionId().toString(),
                c.intervalStart(),
                c.intervalEnd(),
                c.valuationType(),
                c.cellStatus(),
                c.price(),
                c.volumeMw(),
                c.volumeMwh(),
                c.amount(),
                c.currency(),
                c.activeLeaves(),
                c.validFrom(),
                c.validTo(),
                c.knownFrom(),
                c.knownTo()
        );
    }
}
