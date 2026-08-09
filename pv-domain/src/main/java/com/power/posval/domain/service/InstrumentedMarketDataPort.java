package com.power.posval.domain.service;

import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;

import java.time.Instant;
import java.time.YearMonth;

/**
 * Timing decorator for {@link MarketDataPort}.
 * Measures wall-clock time of every lookup call and accumulates into
 * a thread-local {@link PriceCalcTimingStats} so concurrent threads
 * (Kafka consumer pool) don't contaminate each other's measurements.
 */
public class InstrumentedMarketDataPort implements MarketDataPort {

    private final MarketDataPort delegate;
    private final ThreadLocal<PriceCalcTimingStats> threadStats =
        ThreadLocal.withInitial(PriceCalcTimingStats::new);

    public InstrumentedMarketDataPort(MarketDataPort delegate) {
        this.delegate = delegate;
    }

    /** Get the current thread's timing stats. */
    public PriceCalcTimingStats stats() { return threadStats.get(); }

    /** Reset the current thread's stats (call at start of each position). */
    public void resetStats() { threadStats.get().reset(); }

    /** Remove the current thread's stats entirely (call in finally block). */
    public void clearStats() { threadStats.remove(); }

    @Override
    public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupFixing(series, intervalStart);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }

    @Override
    public MarketDataLookup lookupIndex(String series, String refMonthExpression,
                                         DeliveryPeriod deliveryPeriod) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupIndex(series, refMonthExpression, deliveryPeriod);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }

    @Override
    public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar,
                                                Instant asOfDate) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupForwardCurve(series, pillar, asOfDate);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }

    @Override
    public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupFxRate(currencyPair, referenceDate);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }

    @Override
    public MarketDataLookup lookupAtVersion(String series, Instant intervalStart,
                                             long versionId) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupAtVersion(series, intervalStart, versionId);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }

    @Override
    public VolSurfaceLookup lookupVolSurface(String surfaceId, double strikeDelta,
                                               String expiryTenor, Instant asOfDate) {
        long start = System.nanoTime();
        VolSurfaceLookup result = delegate.lookupVolSurface(surfaceId, strikeDelta,
            expiryTenor, asOfDate);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }

    @Override
    public MarketDataLookup lookupSpread(String series, Instant intervalStart) {
        long start = System.nanoTime();
        MarketDataLookup result = delegate.lookupSpread(series, intervalStart);
        threadStats.get().recordLookup(System.nanoTime() - start);
        return result;
    }
}
