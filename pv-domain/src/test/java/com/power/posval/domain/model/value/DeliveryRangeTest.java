package com.power.posval.domain.model.value;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRangeTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void constructsValidRange() {
        var range = new DeliveryRange(YearMonth.of(2025, 1), YearMonth.of(2025, 3), CET);
        assertEquals(YearMonth.of(2025, 1), range.startMonth());
        assertEquals(YearMonth.of(2025, 3), range.endMonth());
    }

    @Test
    void singleMonthRange() {
        var range = new DeliveryRange(YearMonth.of(2025, 6), YearMonth.of(2025, 6), CET);
        assertEquals(range.startMonth(), range.endMonth());
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class,
            () -> new DeliveryRange(YearMonth.of(2025, 3), YearMonth.of(2025, 1), CET));
    }

    @Test
    void rejectsNullStartMonth() {
        assertThrows(NullPointerException.class,
            () -> new DeliveryRange(null, YearMonth.of(2025, 1), CET));
    }

    @Test
    void ofMonthFactory() {
        var range = DeliveryRange.ofMonth(YearMonth.of(2025, 7), CET);
        assertEquals(YearMonth.of(2025, 7), range.startMonth());
        assertEquals(YearMonth.of(2025, 7), range.endMonth());
    }

    @Test
    void startInstant() {
        var range = DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET);
        ZonedDateTime expected = ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET);
        assertEquals(expected, range.startInstant());
    }

    @Test
    void endInstantIsNextMonthStart() {
        var range = DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET);
        ZonedDateTime expected = ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET);
        assertEquals(expected, range.endInstant());
    }

    @Test
    void multiMonthEndInstant() {
        var range = new DeliveryRange(YearMonth.of(2025, 1), YearMonth.of(2025, 3), CET);
        ZonedDateTime expected = ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET);
        assertEquals(expected, range.endInstant());
    }
}
