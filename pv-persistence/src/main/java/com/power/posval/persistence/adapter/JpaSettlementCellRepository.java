package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.entity.SettlementCellEntity;
import com.power.posval.persistence.util.SimpleJsonCodec;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.*;

/**
 * JPA adapter for SettlementCellRepository. §11.1, Pattern #18.
 * Settlement cells are append-only; versioning is derived from the parent
 * position entry's bitemporal state.
 */
public class JpaSettlementCellRepository implements SettlementCellRepository {

    private final Provider<EntityManager> emProvider;
    private final BatchWriter batchWriter;

    @Inject
    public JpaSettlementCellRepository(Provider<EntityManager> emProvider,
                                        BatchWriter batchWriter) {
        this.emProvider = emProvider;
        this.batchWriter = batchWriter;
    }

    @Override
    public void save(SettlementCell cell) {
        emProvider.get().persist(toEntity(cell));
    }

    @Override
    public void saveAll(List<SettlementCell> cells) {
        batchWriter.writeAll(cells.stream().map(this::toEntity).toList());
    }

    @Override
    public boolean existsByPositionId(String tenantId, UUID positionId) {
        Long count = emProvider.get()
            .createQuery("""
                SELECT COUNT(e) FROM SettlementCellEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.positionId = :positionId
                """, Long.class)
            .setParameter("tenantId", tenantId)
            .setParameter("positionId", positionId)
            .setMaxResults(1)
            .getSingleResult();
        return count > 0;
    }

    @Override
    public List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                                Instant rangeStart, Instant rangeEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM SettlementCellEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.positionId = :positionId
                  AND e.intervalStart < :rangeEnd
                  AND e.intervalEnd > :rangeStart
                ORDER BY e.intervalStart
                """, SettlementCellEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("positionId", positionId)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public int deleteByPositionAndInterval(String tenantId, UUID positionId,
                                            Instant intervalStart, Instant intervalEnd) {
        return emProvider.get()
            .createQuery("""
                DELETE FROM SettlementCellEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.positionId = :positionId
                  AND e.intervalStart >= :intervalStart
                  AND e.intervalStart < :intervalEnd
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("positionId", positionId)
            .setParameter("intervalStart", intervalStart)
            .setParameter("intervalEnd", intervalEnd)
            .executeUpdate();
    }

    private SettlementCellEntity toEntity(SettlementCell c) {
        var e = new SettlementCellEntity();
        e.setCellUuid(c.cellId());
        e.setTenantId(c.tenantId());
        e.setPositionId(c.positionId());
        e.setIntervalStart(c.intervalStart());
        e.setIntervalEnd(c.intervalEnd());
        e.setValuationType(c.valuationType());
        e.setCellStatus(c.cellStatus());
        e.setPrice(c.price());
        e.setVolumeMw(c.volumeMw());
        e.setVolumeMwh(c.volumeMwh());
        e.setAmount(c.amount());
        e.setMarketPrice(c.marketPrice());
        e.setMarketAmount(c.marketAmount());
        e.setPnl(c.pnl());
        e.setCurrency(c.currency());
        e.setActiveLeaves(SimpleJsonCodec.setToJson(c.activeLeaves()));
        e.setInputVersionSet(SimpleJsonCodec.mapToJson(c.inputVersionSet()));
        e.setComputedAt(c.computedAt());
        return e;
    }

    private SettlementCell toDomain(SettlementCellEntity e) {
        return new SettlementCell(
            e.getCellUuid(), e.getTenantId(), e.getPositionId(),
            e.getIntervalStart(), e.getIntervalEnd(),
            e.getValuationType(), e.getCellStatus(),
            e.getPrice(), e.getVolumeMw(), e.getVolumeMwh(),
            e.getAmount(),
            e.getMarketPrice(), e.getMarketAmount(), e.getPnl(),
            e.getCurrency(),
            SimpleJsonCodec.jsonToStringSet(e.getActiveLeaves()),
            SimpleJsonCodec.jsonToStringLongMap(e.getInputVersionSet()),
            e.getComputedAt());
    }
}
