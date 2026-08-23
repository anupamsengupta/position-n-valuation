package com.power.posval.domain.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Parsed EPEX DA auction execution report batch, ready for import orchestration.
 * Input DTO produced by AuctionResultParser and consumed by AuctionImportService.
 * {@code contracts} is an unmodifiable list of executed contract lines.
 * Pattern #17, S4.6, DA-VOL-01.
 */
public record AuctionResultBatch(
    String exchange,
    String biddingZone,
    LocalDate deliveryDay,
    BigDecimal exchangeReportedTotalMwh,
    String fileReference,
    List<AuctionResultContract> contracts
) {
    public AuctionResultBatch {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(biddingZone, "biddingZone");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        Objects.requireNonNull(exchangeReportedTotalMwh, "exchangeReportedTotalMwh");
        Objects.requireNonNull(fileReference, "fileReference");
        Objects.requireNonNull(contracts, "contracts");
        if (exchange.isBlank()) {
            throw new IllegalArgumentException("exchange must not be blank");
        }
        if (biddingZone.isBlank()) {
            throw new IllegalArgumentException("biddingZone must not be blank");
        }
        contracts = List.copyOf(contracts);
    }
}
