package com.power.posval.domain.service;

import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.CachedInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AggregatorTest {

    private static final NumericPrecision NP = new DefaultNumericPrecision();

    @Test
    void timeWeightedMw_equalIntervals() {
        var intervals = List.of(
            ci("2025-03-01T00:00:00Z", "2025-03-01T01:00:00Z", "100.0", "100.0"),
            ci("2025-03-01T01:00:00Z", "2025-03-01T02:00:00Z", "200.0", "200.0"));

        BigDecimal result = Aggregators.timeWeightedMw(NP).aggregate(intervals);

        // (100*60 + 200*60) / 120 = 150
        assertEquals(0, new BigDecimal("150").compareTo(result.stripTrailingZeros()));
    }

    @Test
    void timeWeightedMw_unequalIntervals() {
        // 15-min interval at 100 MW + 45-min interval at 200 MW
        var intervals = List.of(
            ci("2025-03-01T00:00:00Z", "2025-03-01T00:15:00Z", "100.0", "25.0"),
            ci("2025-03-01T00:15:00Z", "2025-03-01T01:00:00Z", "200.0", "150.0"));

        BigDecimal result = Aggregators.timeWeightedMw(NP).aggregate(intervals);

        // (100*15 + 200*45) / 60 = (1500 + 9000) / 60 = 175
        assertEquals(0, new BigDecimal("175").compareTo(result.stripTrailingZeros()));
    }

    @Test
    void timeWeightedMw_emptyList() {
        BigDecimal result = Aggregators.timeWeightedMw(NP).aggregate(List.of());
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void sumMwh_addsAll() {
        var intervals = List.of(
            ci("2025-03-01T00:00:00Z", "2025-03-01T01:00:00Z", "100.0", "100.0"),
            ci("2025-03-01T01:00:00Z", "2025-03-01T02:00:00Z", "200.0", "200.0"));

        BigDecimal result = Aggregators.sumMwh().aggregate(intervals);

        assertEquals(0, new BigDecimal("300.0").compareTo(result));
    }

    @Test
    void sumMwh_emptyList() {
        BigDecimal result = Aggregators.sumMwh().aggregate(List.of());
        assertEquals(BigDecimal.ZERO, result);
    }

    private static CachedInterval ci(String start, String end, String mw, String mwh) {
        return new CachedInterval(
            Instant.parse(start), Instant.parse(end),
            new BigDecimal(mw), new BigDecimal(mwh),
            false, "v1", "hash1");
    }
}
