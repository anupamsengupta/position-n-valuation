package com.power.posval.domain.port.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.PositionMonthSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for position queries.
 * Decouples controllers from repository ports.
 */
public interface PositionQueryService {

    List<PositionLedgerEntry> findCurrent(String tenantId, String tradeId, String tradeLegId);

    List<PositionLedgerEntry> findAsOf(String tenantId, String tradeId, String tradeLegId,
                                        Instant businessDate, Instant knowledgeDate);

    List<PositionLedgerEntry> findByDeliveryRange(String tenantId,
                                                    Instant deliveryStart, Instant deliveryEnd);

    List<PositionMonthSummary> monthlySummary(String tenantId,
                                               Instant rangeStart, Instant rangeEnd);

    List<PositionMonthSummary> monthlySummaryByPosition(String tenantId, UUID positionId,
                                                          Instant rangeStart, Instant rangeEnd);
}
