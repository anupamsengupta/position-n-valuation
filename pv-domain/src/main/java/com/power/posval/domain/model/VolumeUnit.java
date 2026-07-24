package com.power.posval.domain.model;

import com.power.posval.domain.port.NumericPrecision;

import java.math.BigDecimal;
import java.time.Duration;

/** Pattern #4, V3.0 §3.2.2, S3. */
public enum VolumeUnit {
    /** Power capacity in MW. Energy = volume × elapsed_hours. */
    MW_CAPACITY {
        @Override
        public BigDecimal toEnergy(BigDecimal volume, Duration elapsed, NumericPrecision np) {
            BigDecimal hours = BigDecimal.valueOf(elapsed.getSeconds())
                .divide(BigDecimal.valueOf(3600),
                    np.scale(NumericPrecision.Domain.ENERGY), np.roundingMode());
            return np.round(volume.multiply(hours), NumericPrecision.Domain.ENERGY);
        }
    },
    /** Energy delivered per period in MWh. Energy = volume. */
    MWH_PER_PERIOD {
        @Override
        public BigDecimal toEnergy(BigDecimal volume, Duration elapsed, NumericPrecision np) {
            return volume;
        }
    };

    /** Convert volume to energy; scale governed by NumericPrecision.ENERGY (TR-048). */
    public abstract BigDecimal toEnergy(BigDecimal volume, Duration elapsed, NumericPrecision np);

    public boolean isFixedDuration() { return true; }
}
