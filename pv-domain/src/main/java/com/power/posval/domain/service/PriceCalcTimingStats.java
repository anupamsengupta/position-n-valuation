package com.power.posval.domain.service;

/**
 * Mutable accumulator for per-position price calculation timing.
 * Tracks total time spent in market data lookups so that compute time
 * can be derived as (totalPriceCalcMs - lookupTotalMs).
 * Reset between position executions.
 */
public final class PriceCalcTimingStats {

    private int lookupCount;
    private long lookupTotalNanos;

    public void recordLookup(long nanos) {
        lookupCount++;
        lookupTotalNanos += nanos;
    }

    public void reset() {
        lookupCount = 0;
        lookupTotalNanos = 0;
    }

    public int lookupCount() { return lookupCount; }
    public long lookupTotalNanos() { return lookupTotalNanos; }
    public long lookupTotalMs() { return lookupTotalNanos / 1_000_000; }
}
