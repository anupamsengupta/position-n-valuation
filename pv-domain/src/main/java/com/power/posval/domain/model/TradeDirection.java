package com.power.posval.domain.model;

/**
 * Buy/Sell trade direction indicator.
 *
 * <p>BUY = long position: quantity stored positive, profit when market price exceeds trade price.
 * SELL = short position: quantity stored negative, profit when market price falls below trade price.
 *
 * <p>Pattern #4 (Domain Enum). FR-034, D-1, S1.
 * Regulatory mapping: REMIT Table 1 Field 16, EMIR RTS Field 29, MiFID II RTS 22 Field 28.
 */
public enum TradeDirection {
    BUY,   // Long position: signed quantity is positive
    SELL;  // Short position: signed quantity is negative

    /**
     * Returns the arithmetic sign for this direction.
     * BUY -> +1, SELL -> -1.
     *
     * <p>Used in {@code DefaultTradeCaptureHandler} to compute
     * {@code signedQuantity = abs(quantity) * direction.sign()}.
     * FR-034.
     */
    public int sign() {
        return this == BUY ? 1 : -1;
    }
}
