package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.PositionMonthSummary;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.entity.SettlementCellEntity;
import com.power.posval.persistence.util.SimpleJsonCodec;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
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

    @Override
    public List<PositionMonthSummary> findMonthlySummary(String tenantId,
                                                          Instant rangeStart,
                                                          Instant rangeEnd) {
        return executeSummaryQuery(tenantId, null, rangeStart, rangeEnd);
    }

    @Override
    public List<PositionMonthSummary> findMonthlySummaryByPosition(String tenantId,
                                                                     UUID positionId,
                                                                     Instant rangeStart,
                                                                     Instant rangeEnd) {
        return executeSummaryQuery(tenantId, positionId, rangeStart, rangeEnd);
    }

    @SuppressWarnings("unchecked")
    private List<PositionMonthSummary> executeSummaryQuery(String tenantId,
                                                            UUID positionId,
                                                            Instant rangeStart,
                                                            Instant rangeEnd) {
        // FR-035: MW = time-weighted average; MWh = sum; amounts = sum.
        // Avg price = totalAmount / totalMwh (volume-weighted).
        String positionFilter = positionId != null
            ? "AND sc.position_id = :positionId" : "";

        var query = emProvider.get()
            .createNativeQuery("""
                SELECT sc.position_id,
                       sc.tenant_id,
                       ple.trade_id,
                       ple.trade_leg_id,
                       date_trunc('month', sc.interval_start AT TIME ZONE 'UTC') AS delivery_month,
                       SUM(sc.volume_mwh)                                        AS total_mwh,
                       SUM(sc.volume_mw * EXTRACT(EPOCH FROM (sc.interval_end - sc.interval_start)) / 60.0)
                           / NULLIF(SUM(EXTRACT(EPOCH FROM (sc.interval_end - sc.interval_start)) / 60.0), 0)
                                                                                  AS avg_mw,
                       SUM(sc.amount)                                            AS total_amount,
                       SUM(sc.market_amount)                                     AS total_market_amount,
                       SUM(sc.pnl)                                               AS total_pnl,
                       sc.currency,
                       COUNT(*)                                                  AS cell_count
                FROM valuation.settlement_cell sc
                JOIN position.position_ledger_entry ple
                  ON ple.entry_uuid = sc.position_id AND ple.tenant_id = sc.tenant_id
                WHERE sc.tenant_id = :tenantId
                  AND sc.interval_start < :rangeEnd
                  AND sc.interval_end > :rangeStart
                  """ + positionFilter + """

                GROUP BY sc.position_id, sc.tenant_id, ple.trade_id, ple.trade_leg_id,
                         date_trunc('month', sc.interval_start AT TIME ZONE 'UTC'), sc.currency
                ORDER BY delivery_month, ple.trade_id, ple.trade_leg_id
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd);

        if (positionId != null) {
            query.setParameter("positionId", positionId);
        }

        return ((List<Object[]>) query.getResultList()).stream()
            .map(this::mapToSummary)
            .toList();
    }

    private PositionMonthSummary mapToSummary(Object[] row) {
        UUID posId = (UUID) row[0];
        String tenant = (String) row[1];
        String tradeId = (String) row[2];
        String tradeLegId = (String) row[3];
        java.sql.Timestamp monthTs = (java.sql.Timestamp) row[4];
        YearMonth month = YearMonth.from(monthTs.toInstant().atOffset(ZoneOffset.UTC));
        BigDecimal totalMwh = toBigDecimal(row[5]);
        BigDecimal avgMw = toBigDecimal(row[6]);
        BigDecimal totalAmount = toBigDecimal(row[7]);
        BigDecimal totalMarketAmount = toBigDecimal(row[8]);
        BigDecimal totalPnl = toBigDecimal(row[9]);
        String currency = (String) row[10];
        int cellCount = ((Number) row[11]).intValue();

        // Volume-weighted average prices
        BigDecimal avgPrice = totalMwh != null && totalMwh.signum() != 0
            ? totalAmount.divide(totalMwh, 8, RoundingMode.HALF_UP) : null;
        BigDecimal avgMarketPrice = totalMarketAmount != null && totalMwh != null && totalMwh.signum() != 0
            ? totalMarketAmount.divide(totalMwh, 8, RoundingMode.HALF_UP) : null;

        return new PositionMonthSummary(
            posId, tenant, tradeId, tradeLegId, month,
            totalMwh, avgMw, totalAmount, totalMarketAmount, totalPnl,
            avgPrice, avgMarketPrice, currency, cellCount);
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
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
