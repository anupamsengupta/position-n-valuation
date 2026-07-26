package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.persistence.entity.SettlementCellEntity;
import com.power.posval.persistence.util.SimpleJsonCodec;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.*;

/**
 * JPA adapter for SettlementCellRepository. §11.1, Pattern #18.
 * Bitemporal cell persistence.
 */
public class JpaSettlementCellRepository implements SettlementCellRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaSettlementCellRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(SettlementCell cell) {
        emProvider.get().persist(toEntity(cell));
    }

    @Override
    public void saveAll(List<SettlementCell> cells) {
        if (cells.isEmpty()) return;
        EntityManager em = emProvider.get();
        int batchSize = 100;
        for (int i = 0; i < cells.size(); i++) {
            em.persist(toEntity(cells.get(i)));
            if ((i + 1) % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
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
                  AND e.knownTo IS NULL
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
        e.setCurrency(c.currency());
        e.setActiveLeaves(SimpleJsonCodec.setToJson(c.activeLeaves()));
        e.setInputVersionSet(SimpleJsonCodec.mapToJson(c.inputVersionSet()));
        e.setValidFrom(c.validFrom());
        e.setValidTo(c.validTo());
        e.setKnownFrom(c.knownFrom());
        e.setKnownTo(c.knownTo());
        return e;
    }

    private SettlementCell toDomain(SettlementCellEntity e) {
        return new SettlementCell(
            e.getCellUuid(), e.getTenantId(), e.getPositionId(),
            e.getIntervalStart(), e.getIntervalEnd(),
            e.getValuationType(), e.getCellStatus(),
            e.getPrice(), e.getVolumeMw(), e.getVolumeMwh(),
            e.getAmount(), e.getCurrency(),
            SimpleJsonCodec.jsonToStringSet(e.getActiveLeaves()),
            SimpleJsonCodec.jsonToStringLongMap(e.getInputVersionSet()),
            e.getValidFrom(), e.getValidTo(),
            e.getKnownFrom(), e.getKnownTo());
    }
}
