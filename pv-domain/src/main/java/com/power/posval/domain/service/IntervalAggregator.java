package com.power.posval.domain.service;

import com.power.posval.domain.port.cache.CachedInterval;

import java.util.List;

/**
 * Aggregation function for rollup computation.
 * FR-085: granularity conversion rules.
 * FR-090: net_mw = time-weighted average; net_mwh = sum.
 * Pattern #9, S7.
 */
@FunctionalInterface
public interface IntervalAggregator<T> {
    T aggregate(List<CachedInterval> sourceIntervals);
}
