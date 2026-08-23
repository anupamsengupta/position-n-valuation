package com.power.posval.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Emitted by AuctionImportService when a batch auction result import fails
 * (validation or import error; AuctionImportSession status = VALIDATION_FAILED or IMPORT_FAILED).
 * Triggers an OperationalAlert (AUCTION_INGESTION, CRITICAL) in the alert service.
 * Pattern #14, S4.5, DA-VOL-01.
 */
public record AuctionImportFailed(
    String tenantId,
    UUID sessionId,
    String biddingZone,
    LocalDate deliveryDay,
    List<String> errors,
    Instant eventTime
) {
    public AuctionImportFailed {
        errors = List.copyOf(errors);
    }
}
