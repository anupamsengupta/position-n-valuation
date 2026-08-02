package com.power.posval.domain.port.marketdata;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.value.DeliveryPeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

/**
 * Read-only port for market data access. Version pinning for reproducibility (FR-048f).
 * Pattern #18, FR-060–FR-063, S4.
 */
public interface MarketDataPort {

    /** Lookup a fixing/spot price for a series at a given interval start. */
    MarketDataLookup lookupFixing(String series, Instant intervalStart);

    /** Lookup a macro index value with reference-month expression. */
    MarketDataLookup lookupIndex(String series, String refMonthExpression, DeliveryPeriod deliveryPeriod);

    /** Lookup a forward curve pillar. */
    MarketDataLookup lookupForwardCurve(String series, YearMonth pillar, Instant asOfDate);

    /** Lookup an FX rate. */
    MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate);

    /** Lookup at a specific version for reproducibility (FR-048f input_version_set). */
    MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long versionId);

    /** Lookup a volatility surface point (strikeDelta x expiryTenor). */
    default VolSurfaceLookup lookupVolSurface(String surfaceId, double strikeDelta,
                                               String expiryTenor, Instant asOfDate) {
        return new VolSurfaceLookup(BigDecimal.ZERO, 0L, surfaceId, strikeDelta,
            expiryTenor, asOfDate, QualityState.PROVISIONAL);
    }

    /** Lookup a spread/basis value. Keyed identically to fixings. */
    default MarketDataLookup lookupSpread(String series, Instant intervalStart) {
        return new MarketDataLookup(BigDecimal.ZERO, 0L, series, intervalStart,
            QualityState.PROVISIONAL);
    }
}
