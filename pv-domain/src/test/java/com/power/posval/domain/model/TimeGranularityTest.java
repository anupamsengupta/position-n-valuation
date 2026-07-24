package com.power.posval.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TimeGranularityTest {

    @Test void min5Duration() { assertEquals(Duration.ofMinutes(5), TimeGranularity.MIN_5.getFixedDuration()); }
    @Test void min15Duration() { assertEquals(Duration.ofMinutes(15), TimeGranularity.MIN_15.getFixedDuration()); }
    @Test void min30Duration() { assertEquals(Duration.ofMinutes(30), TimeGranularity.MIN_30.getFixedDuration()); }
    @Test void hourlyDuration() { assertEquals(Duration.ofHours(1), TimeGranularity.HOURLY.getFixedDuration()); }

    @Test
    void dailyHasVariableDuration() {
        assertFalse(TimeGranularity.DAILY.isFixedDuration());
        assertThrows(UnsupportedOperationException.class, () -> TimeGranularity.DAILY.getFixedDuration());
    }

    @Test
    void monthlyHasVariableDuration() {
        assertFalse(TimeGranularity.MONTHLY.isFixedDuration());
        assertThrows(UnsupportedOperationException.class, () -> TimeGranularity.MONTHLY.getFixedDuration());
    }

    @Test
    void subDailyGranularities() {
        assertTrue(TimeGranularity.MIN_5.isSubDaily());
        assertTrue(TimeGranularity.MIN_15.isSubDaily());
        assertTrue(TimeGranularity.MIN_30.isSubDaily());
        assertTrue(TimeGranularity.HOURLY.isSubDaily());
        assertFalse(TimeGranularity.DAILY.isSubDaily());
        assertFalse(TimeGranularity.MONTHLY.isSubDaily());
    }

    @Test
    void fixedDurationGranularitiesReportTrue() {
        assertTrue(TimeGranularity.MIN_5.isFixedDuration());
        assertTrue(TimeGranularity.MIN_15.isFixedDuration());
        assertTrue(TimeGranularity.MIN_30.isFixedDuration());
        assertTrue(TimeGranularity.HOURLY.isFixedDuration());
    }
}
