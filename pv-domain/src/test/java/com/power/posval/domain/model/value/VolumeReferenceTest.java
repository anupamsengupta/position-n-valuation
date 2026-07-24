package com.power.posval.domain.model.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VolumeReferenceTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime FROM = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET);
    private static final ZonedDateTime TO = ZonedDateTime.of(2025, 12, 31, 23, 59, 59, 0, CET);

    private VolumeReference.Builder validBuilder() {
        return VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId("LEG-1")
            .tradeId("T-100")
            .multiplier(BigDecimal.ONE)
            .volumeSeriesKey(new SeriesKey("VS-1"))
            .effectiveFrom(FROM)
            .effectiveTo(TO);
    }

    @Test
    void builderCreatesValidReference() {
        VolumeReference ref = validBuilder().build();
        assertEquals("LEG-1", ref.tradeLegId());
        assertEquals("T-100", ref.tradeId());
        assertEquals(0, BigDecimal.ONE.compareTo(ref.multiplier()));
    }

    @Test
    void isFixedProfileWhenMultiplierOneAndNoAsset() {
        VolumeReference ref = validBuilder().multiplier(BigDecimal.ONE).assetId(null).build();
        assertTrue(ref.isFixedProfile());
    }

    @Test
    void isNotFixedProfileWhenAssetSet() {
        VolumeReference ref = validBuilder().assetId("WIND-1").build();
        assertFalse(ref.isFixedProfile());
    }

    @Test
    void isNotFixedProfileWhenMultiplierLessThanOne() {
        VolumeReference ref = validBuilder().multiplier(new BigDecimal("0.5")).build();
        assertFalse(ref.isFixedProfile());
    }

    @Test
    void ppaReferenceWithAssetAndFractionalMultiplier() {
        VolumeReference ref = validBuilder()
            .assetId("WIND-1")
            .multiplier(new BigDecimal("0.75"))
            .meteredSeriesKey(new SeriesKey("MTR-WIND-1"))
            .build();
        assertFalse(ref.isFixedProfile());
        assertEquals("WIND-1", ref.assetId());
        assertEquals(new BigDecimal("0.75"), ref.multiplier());
    }

    @Test
    void multiplierZeroThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().multiplier(BigDecimal.ZERO).build());
    }

    @Test
    void multiplierNegativeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().multiplier(new BigDecimal("-0.5")).build());
    }

    @Test
    void multiplierAboveOneThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().multiplier(new BigDecimal("1.01")).build());
    }

    @Test
    void effectiveToBeforeFromThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().effectiveFrom(TO).effectiveTo(FROM).build());
    }

    @Test
    void nullTradeLegIdThrows() {
        assertThrows(NullPointerException.class,
            () -> validBuilder().tradeLegId(null).build());
    }
}
