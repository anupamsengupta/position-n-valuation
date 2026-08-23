package com.power.posval.domain.model.value;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Identifies a single DA auction session by exchange, bidding zone, and delivery day.
 * Used as the natural business key for idempotency checks in AuctionImportSession.
 * Pattern #3, S4.1, DA-VOL-01.
 */
public record AuctionSessionKey(
    String exchange,
    String biddingZone,
    LocalDate deliveryDay
) {
    public AuctionSessionKey {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(biddingZone, "biddingZone");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        if (exchange.isBlank()) {
            throw new IllegalArgumentException("exchange must not be blank");
        }
        if (biddingZone.isBlank()) {
            throw new IllegalArgumentException("biddingZone must not be blank");
        }
    }
}
