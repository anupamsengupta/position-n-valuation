package com.power.posval.domain.model.value;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPeriodTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void constructsValidPeriod() {
        ZonedDateTime start = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET);
        ZonedDateTime end = ZonedDateTime.of(2025, 2, 1, 0, 0, 0, 0, CET);
        var dp = new DeliveryPeriod(start, end, CET);
        assertEquals(start, dp.start());
        assertEquals(end, dp.end());
        assertEquals(CET, dp.deliveryTimezone());
    }

    @Test
    void rejectsEndBeforeStart() {
        ZonedDateTime start = ZonedDateTime.of(2025, 2, 1, 0, 0, 0, 0, CET);
        ZonedDateTime end = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET);
        assertThrows(IllegalArgumentException.class, () -> new DeliveryPeriod(start, end, CET));
    }

    @Test
    void rejectsEqualStartAndEnd() {
        ZonedDateTime same = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET);
        assertThrows(IllegalArgumentException.class, () -> new DeliveryPeriod(same, same, CET));
    }

    @Test
    void rejectsNullStart() {
        ZonedDateTime end = ZonedDateTime.of(2025, 2, 1, 0, 0, 0, 0, CET);
        assertThrows(NullPointerException.class, () -> new DeliveryPeriod(null, end, CET));
    }

    @Test
    void ofFactoryConvertsZones() {
        ZonedDateTime start = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime end = ZonedDateTime.of(2025, 2, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        var dp = DeliveryPeriod.of(start, end, CET);
        assertEquals(CET, dp.start().getZone());
        assertEquals(CET, dp.end().getZone());
    }

    @Test
    void containsHalfOpen() {
        ZonedDateTime start = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET);
        ZonedDateTime end = ZonedDateTime.of(2025, 1, 2, 0, 0, 0, 0, CET);
        var dp = new DeliveryPeriod(start, end, CET);

        assertTrue(dp.contains(start));                                         // start is included
        assertFalse(dp.contains(end));                                          // end is excluded
        assertTrue(dp.contains(ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, CET))); // mid is included
        assertFalse(dp.contains(ZonedDateTime.of(2024, 12, 31, 23, 0, 0, 0, CET))); // before
    }
}
