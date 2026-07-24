package com.power.posval.domain.model.value;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TimeRangeTest {

    private static final Instant T1 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant MID = Instant.parse("2025-01-15T00:00:00Z");

    @Test
    void closedRange() {
        var range = TimeRange.closed(T1, T2);
        assertEquals(T1, range.from());
        assertEquals(T2, range.to());
        assertFalse(range.isOpen());
    }

    @Test
    void openRange() {
        var range = TimeRange.open(T1);
        assertEquals(T1, range.from());
        assertNull(range.to());
        assertTrue(range.isOpen());
    }

    @Test
    void rejectsNullFrom() {
        assertThrows(NullPointerException.class, () -> new TimeRange(null, T2));
    }

    @Test
    void rejectsToBeforeFrom() {
        assertThrows(IllegalArgumentException.class, () -> TimeRange.closed(T2, T1));
    }

    @Test
    void rejectsEqualFromTo() {
        assertThrows(IllegalArgumentException.class, () -> TimeRange.closed(T1, T1));
    }

    @Test
    void containsHalfOpenClosed() {
        var range = TimeRange.closed(T1, T2);
        assertTrue(range.contains(T1));
        assertFalse(range.contains(T2));
        assertTrue(range.contains(MID));
    }

    @Test
    void containsOpenEnded() {
        var range = TimeRange.open(T1);
        assertTrue(range.contains(T1));
        assertTrue(range.contains(MID));
        assertTrue(range.contains(Instant.parse("2099-12-31T23:59:59Z")));
        assertFalse(range.contains(Instant.parse("2024-12-31T23:59:59Z")));
    }
}
