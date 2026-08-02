package com.power.posval.domain.port.marketdata;

/**
 * Discriminator for market data categories in cache and repository layers.
 * S4, FR-060–FR-063.
 */
public enum MarketDataType {
    FIXING,
    FORWARD_CURVE,
    FX_RATE,
    INDEX,
    VOL_SURFACE,
    SPREAD
}
