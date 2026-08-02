package com.power.posval.app.dto;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketDataRequest(
        String tenantId,
        String series,
        String intervalStart,
        String pillar,
        String asOfDate,
        String refMonth,
        String currencyPair,
        String referenceDate,
        String value,
        String qualityState
) {
    public MarketDataLookup toLookup() {
        QualityState qs = qualityState != null ? QualityState.valueOf(qualityState) : QualityState.VALIDATED;
        return new MarketDataLookup(
                new BigDecimal(value),
                1L,
                series != null ? series : currencyPair,
                Instant.now(),
                qs
        );
    }
}
