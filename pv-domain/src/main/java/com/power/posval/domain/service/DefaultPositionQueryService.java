package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.PositionMonthSummary;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.service.PositionQueryService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link PositionQueryService}.
 * Delegates to repository ports.
 */
public class DefaultPositionQueryService implements PositionQueryService {

    private final PositionLedgerRepository ledgerRepo;
    private final SettlementCellRepository cellRepo;

    public DefaultPositionQueryService(PositionLedgerRepository ledgerRepo,
                                        SettlementCellRepository cellRepo) {
        this.ledgerRepo = ledgerRepo;
        this.cellRepo = cellRepo;
    }

    @Override
    public List<PositionLedgerEntry> findCurrent(String tenantId, String tradeId, String tradeLegId) {
        return ledgerRepo.findCurrentByTradeLeg(tenantId, tradeId, tradeLegId);
    }

    @Override
    public List<PositionLedgerEntry> findAsOf(String tenantId, String tradeId, String tradeLegId,
                                                Instant businessDate, Instant knowledgeDate) {
        return ledgerRepo.findAsOf(tenantId, tradeId, tradeLegId, businessDate, knowledgeDate);
    }

    @Override
    public List<PositionLedgerEntry> findByDeliveryRange(String tenantId,
                                                          Instant deliveryStart, Instant deliveryEnd) {
        return ledgerRepo.findAllByDeliveryRange(tenantId, deliveryStart, deliveryEnd);
    }

    @Override
    public List<PositionMonthSummary> monthlySummary(String tenantId,
                                                       Instant rangeStart, Instant rangeEnd) {
        return cellRepo.findMonthlySummary(tenantId, rangeStart, rangeEnd);
    }

    @Override
    public List<PositionMonthSummary> monthlySummaryByPosition(String tenantId, UUID positionId,
                                                                  Instant rangeStart, Instant rangeEnd) {
        return cellRepo.findMonthlySummaryByPosition(tenantId, positionId, rangeStart, rangeEnd);
    }
}
