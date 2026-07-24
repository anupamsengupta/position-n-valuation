package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PositionLedgerEntryTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private PositionLedgerEntry.Builder validBuilder() {
        return PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TENANT-1")
            .tradeId("T-100")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(new BigDecimal("50.0"))
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .portfolioId("PF-A")
            .deliveryPointId("DP-1")
            .originType("BILATERAL_TRADE")
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .status("ACTIVE");
    }

    @Test
    void buildsWithAllRequiredFields() {
        PositionLedgerEntry entry = validBuilder().build();
        assertEquals("TENANT-1", entry.tenantId());
        assertEquals("T-100", entry.tradeId());
        assertEquals("LEG-1", entry.tradeLegId());
        assertEquals(1, entry.tradeVersion());
        assertEquals(0, new BigDecimal("50.0").compareTo(entry.quantity()));
        assertEquals(VolumeUnit.MW_CAPACITY, entry.volumeUnit());
    }

    @Test
    void isCurrentKnowledgeWhenKnownToNull() {
        PositionLedgerEntry entry = validBuilder().knownTo(null).build();
        assertTrue(entry.isCurrentKnowledge());
    }

    @Test
    void isNotCurrentKnowledgeWhenKnownToSet() {
        PositionLedgerEntry entry = validBuilder().knownTo(Instant.now()).build();
        assertFalse(entry.isCurrentKnowledge());
    }

    @Test
    void missingTenantIdThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().tenantId(null).build());
    }

    @Test
    void missingTradeIdThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().tradeId(null).build());
    }

    @Test
    void missingTradeLegIdThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().tradeLegId(null).build());
    }

    @Test
    void missingDeliveryRangeThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().deliveryRange(null).build());
    }

    @Test
    void missingQuantityThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().quantity(null).build());
    }

    @Test
    void missingVolumeUnitThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().volumeUnit(null).build());
    }

    @Test
    void missingPriceExpressionIdThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().priceExpressionId(null).build());
    }

    @Test
    void missingValidFromThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().validFrom(null).build());
    }

    @Test
    void missingKnownFromThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().knownFrom(null).build());
    }

    @Test
    void optionalFieldsCanBeNull() {
        PositionLedgerEntry entry = validBuilder()
            .volumeSeriesKey(null)
            .cascadeParentId(null)
            .validTo(null)
            .knownTo(null)
            .amendmentReason(null)
            .build();
        assertNull(entry.volumeSeriesKey());
        assertNull(entry.cascadeParentId());
        assertNull(entry.validTo());
        assertNull(entry.amendmentReason());
    }

    @Test
    void volumeSeriesKeyAccessor() {
        var key = new SeriesKey("VS-100");
        PositionLedgerEntry entry = validBuilder().volumeSeriesKey(key).build();
        assertEquals(key, entry.volumeSeriesKey());
    }
}
