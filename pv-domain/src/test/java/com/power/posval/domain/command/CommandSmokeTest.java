package com.power.posval.domain.command;

import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandSmokeTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET);
    private static final ZonedDateTime END = ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET);
    private static final Instant NOW = Instant.now();

    @Test
    void tradeCapture() {
        var dp = new DeliveryPeriod(START, END, CET);
        var cmd = new TradeCapture(
            "T-100", 1, "LEG-1", "TENANT-1", dp,
            new BigDecimal("50"), VolumeUnit.MW_CAPACITY, UUID.randomUUID(),
            "PF-A", "DP-1", "BILATERAL_TRADE", NOW,
            null, BigDecimal.ONE, new SeriesKey("VS-1"), null);
        assertEquals("T-100", cmd.tradeId());
        assertEquals(1, cmd.tradeVersion());
        assertNull(cmd.assetId());
        assertNull(cmd.meteredSeriesKey());
    }

    @Test
    void tradeAmend() {
        var cmd = new TradeAmend(
            "T-100", 2, "LEG-1", "TENANT-1",
            "BACKDATED_CORRECTION", NOW,
            new BigDecimal("60"), null, null, null, null);
        assertEquals(2, cmd.tradeVersion());
        assertEquals("BACKDATED_CORRECTION", cmd.amendmentReason());
        assertNull(cmd.priceExpressionId());
    }

    @Test
    void tradeCancel() {
        var cmd = new TradeCancel("T-100", 3, "LEG-1", "TENANT-1", "VOID_AB_INITIO", NOW);
        assertEquals("VOID_AB_INITIO", cmd.cancellationType());
        assertEquals("TENANT-1", cmd.tenantId());
    }
}
