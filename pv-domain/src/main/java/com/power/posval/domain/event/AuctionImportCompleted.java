package com.power.posval.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Emitted by AuctionImportService when a batch auction result import completes
 * successfully (all contracts imported, AuctionImportSession status = IMPORTED).
 * Subscribers may use this to trigger downstream enrichment steps.
 * Pattern #14, S4.5, DA-VOL-01.
 */
public record AuctionImportCompleted(
    String tenantId,
    UUID sessionId,
    String biddingZone,
    LocalDate deliveryDay,
    int tradeCount,
    Instant eventTime
) {}
