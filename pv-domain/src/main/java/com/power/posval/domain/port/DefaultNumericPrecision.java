package com.power.posval.domain.port;

/**
 * Immutable default precision configuration.
 * Suitable for EU power (EPEX/EEX/NORDPOOL/XBID) deployments.
 * Override via Guice @Named binding for commodity-specific precision
 * (e.g., gas = VOLUME scale 3, oil = PRICE scale 4).
 */
public record DefaultNumericPrecision() implements NumericPrecision {

    @Override
    public int scale(Domain domain) {
        return switch (domain) {
            case MONETARY     -> 4;
            case PRICE        -> 8;
            case VOLUME       -> 8;
            case ENERGY       -> 8;
            case MULTIPLIER   -> 8;
            case INTERMEDIATE -> 10;
        };
    }

    @Override
    public int precision(Domain domain) {
        return switch (domain) {
            case MONETARY     -> 20;
            case PRICE        -> 20;
            case VOLUME       -> 18;
            case ENERGY       -> 20;
            case MULTIPLIER   -> 10;
            case INTERMEDIATE -> 24;
        };
    }
}
