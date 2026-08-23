package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * JSON-serializable DTO for {@code AuctionImportSession} domain model (DA-VOL-01).
 */
public record DaImportSessionDto(
    UUID sessionId,
    String tenantId,
    String exchange,
    String biddingZone,
    LocalDate deliveryDay,
    Instant importTimestamp,
    String status,
    BigDecimal exchangeReportedTotalMwh,
    BigDecimal importedTotalMwh,
    int intervalCount,
    String fileReference,
    List<String> tradeIds,
    List<String> validationErrors,
    Instant createdAt,
    Instant completedAt
) {}
