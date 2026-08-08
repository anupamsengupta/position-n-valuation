package com.power.posval.domain.service;

import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.service.SettlementQueryService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link SettlementQueryService}.
 * Delegates to repository ports.
 */
public class DefaultSettlementQueryService implements SettlementQueryService {

    private final SettlementCellRepository cellRepo;

    public DefaultSettlementQueryService(SettlementCellRepository cellRepo) {
        this.cellRepo = cellRepo;
    }

    @Override
    public List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                                 Instant rangeStart, Instant rangeEnd) {
        return cellRepo.findByPosition(tenantId, positionId, rangeStart, rangeEnd);
    }
}
