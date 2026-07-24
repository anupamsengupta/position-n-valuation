package com.power.posval.domain.model;

import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class VolumeUnitTest {

    private final NumericPrecision np = new DefaultNumericPrecision();

    @Test
    void mwCapacityConvertsToEnergy() {
        // 100 MW × 1 hour = 100 MWh
        BigDecimal volume = new BigDecimal("100");
        Duration oneHour = Duration.ofHours(1);
        BigDecimal energy = VolumeUnit.MW_CAPACITY.toEnergy(volume, oneHour, np);
        assertEquals(0, new BigDecimal("100.00000000").compareTo(energy));
    }

    @Test
    void mwCapacity15MinInterval() {
        // 200 MW × 0.25 hour = 50 MWh
        BigDecimal volume = new BigDecimal("200");
        Duration fifteenMin = Duration.ofMinutes(15);
        BigDecimal energy = VolumeUnit.MW_CAPACITY.toEnergy(volume, fifteenMin, np);
        assertEquals(0, new BigDecimal("50.00000000").compareTo(energy));
    }

    @Test
    void mwCapacity30MinInterval() {
        // 50 MW × 0.5 hours = 25 MWh
        BigDecimal volume = new BigDecimal("50");
        Duration halfHour = Duration.ofMinutes(30);
        BigDecimal energy = VolumeUnit.MW_CAPACITY.toEnergy(volume, halfHour, np);
        assertEquals(0, new BigDecimal("25.00000000").compareTo(energy));
    }

    @Test
    void mwhPerPeriodIsIdentity() {
        BigDecimal volume = new BigDecimal("42.123456");
        BigDecimal energy = VolumeUnit.MWH_PER_PERIOD.toEnergy(volume, Duration.ofHours(1), np);
        assertSame(volume, energy);
    }

    @Test
    void mwCapacityRoundsToEnergyScale() {
        // 33.33 MW × 1 hour = 33.33000000 MWh (8 decimal places)
        BigDecimal volume = new BigDecimal("33.33");
        BigDecimal energy = VolumeUnit.MW_CAPACITY.toEnergy(volume, Duration.ofHours(1), np);
        assertEquals(8, energy.scale());
    }
}
