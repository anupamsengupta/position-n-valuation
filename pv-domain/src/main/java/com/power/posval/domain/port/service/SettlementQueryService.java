package com.power.posval.domain.port.service;

import com.power.posval.domain.model.SettlementCell;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for settlement cell queries.
 * Decouples controllers from repository ports.
 */
public interface SettlementQueryService {

    List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                         Instant rangeStart, Instant rangeEnd);
}
