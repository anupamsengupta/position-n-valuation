package com.power.posval.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StruckMarkTest {

    @Test
    void constructionWithRequiredFields() {
        var mark = testMark();

        assertEquals("TN_0042", mark.tenantId());
        assertEquals(YearMonth.of(2025, 3), mark.deliveryMonth());
        assertEquals(LocalDate.of(2025, 3, 1), mark.strikeDate());
        assertFalse(mark.isRestrike());
    }

    @Test
    void restrikeHasSupersedesId() {
        var mark = new StruckMark(
            "TN_0042", UUID.randomUUID(),
            YearMonth.of(2025, 3), LocalDate.of(2025, 3, 2),
            new BigDecimal("9000.00"), "EUR",
            Map.of("EPEX", 43L), null, Map.of(),
            1L, 100L, true, Instant.now());

        assertTrue(mark.isRestrike());
        assertEquals(100L, mark.supersedesId());
    }

    @Test
    void nullRequiredFieldsThrow() {
        assertThrows(NullPointerException.class, () -> new StruckMark(
            null, UUID.randomUUID(), YearMonth.of(2025, 3),
            LocalDate.now(), BigDecimal.ONE, "EUR", Map.of(),
            null, null, 0L, null, false, Instant.now()));
    }

    private StruckMark testMark() {
        return new StruckMark(
            "TN_0042", UUID.randomUUID(),
            YearMonth.of(2025, 3), LocalDate.of(2025, 3, 1),
            new BigDecimal("8500.00"), "EUR",
            Map.of("EPEX", 42L), null, Map.of(),
            0L, null, false, Instant.now());
    }
}
