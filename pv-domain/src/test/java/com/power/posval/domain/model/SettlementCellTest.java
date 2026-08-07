package com.power.posval.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCellTest {

    @Test
    void constructionWithRequiredFields() {
        var cell = testCell();

        assertEquals("TN_0042", cell.tenantId());
        assertEquals("SETTLEMENT", cell.valuationType());
        assertEquals("PROVISIONAL", cell.cellStatus());
        assertEquals(0, new BigDecimal("85.00").compareTo(cell.price()));
        assertNotNull(cell.computedAt());
    }

    @Test
    void nullRequiredFieldsThrow() {
        assertThrows(NullPointerException.class, () -> new SettlementCell(
            null, "TN", UUID.randomUUID(),
            Instant.now(), Instant.now(),
            "S", "P", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, "EUR", null, null,
            Instant.now()));
    }

    @Test
    void nullComputedAtThrows() {
        assertThrows(NullPointerException.class, () -> new SettlementCell(
            UUID.randomUUID(), "TN", UUID.randomUUID(),
            Instant.now(), Instant.now(),
            "S", "P", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, "EUR", Set.of(), Map.of(),
            null));
    }

    private SettlementCell testCell() {
        return new SettlementCell(
            UUID.randomUUID(), "TN_0042", UUID.randomUUID(),
            Instant.parse("2025-03-01T00:00:00Z"),
            Instant.parse("2025-03-01T01:00:00Z"),
            "SETTLEMENT", "PROVISIONAL",
            new BigDecimal("85.00"), new BigDecimal("100.0"),
            new BigDecimal("100.0"), new BigDecimal("8500.00"),
            "EUR", Set.of("da-leaf"), Map.of("EPEX", 42L),
            Instant.now());
    }
}
