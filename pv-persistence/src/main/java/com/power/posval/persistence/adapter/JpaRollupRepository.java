package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * JPA adapter for RollupRepository. §13.1, Pattern #18.
 * Queries rollup materialized view / table.
 */
public class JpaRollupRepository implements RollupRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaRollupRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public List<RollupCell> findByRange(String tenantId,
                                         String deliveryPointId,
                                         String portfolioId,
                                         Instant rangeStart,
                                         Instant rangeEnd,
                                         TimeGranularity granularity) {
        return emProvider.get()
            .createNativeQuery("""
                SELECT tenant_id, delivery_point_id, portfolio_id,
                       interval_start, interval_end, granularity,
                       net_mw, net_mwh, is_peak, settled_value,
                       market_value, pnl, forward_mark_value,
                       calendar_version, version_hash, currency
                FROM volume_series.rollup_cell
                WHERE tenant_id = :tenantId
                  AND delivery_point_id = :deliveryPointId
                  AND portfolio_id = :portfolioId
                  AND interval_start < :rangeEnd
                  AND interval_end > :rangeStart
                  AND granularity = :granularity
                ORDER BY interval_start
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("deliveryPointId", deliveryPointId)
            .setParameter("portfolioId", portfolioId)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd)
            .setParameter("granularity", granularity.name())
            .getResultList()
            .stream()
            .map(row -> mapToRollupCell((Object[]) row))
            .toList();
    }

    @Override
    public void refresh(String tenantId,
                         Instant rangeStart,
                         Instant rangeEnd,
                         TimeGranularity granularity) {
        emProvider.get()
            .createNativeQuery("""
                REFRESH MATERIALIZED VIEW CONCURRENTLY volume_series.rollup_cell
                """)
            .executeUpdate();
    }

    @Override
    public void saveAll(String tenantId, List<RollupCell> cells) {
        var em = emProvider.get();
        for (RollupCell cell : cells) {
            em.createNativeQuery("""
                INSERT INTO volume_series.rollup_cell
                  (tenant_id, delivery_point_id, portfolio_id,
                   interval_start, interval_end, granularity,
                   net_mw, net_mwh, is_peak,
                   settled_value, market_value, pnl, forward_mark_value,
                   currency, calendar_version, version_hash, refreshed_at)
                VALUES (:tenantId, :dpId, :portId,
                        :start, :end, :granularity,
                        :netMw, :netMwh, :isPeak,
                        :settledValue, :marketValue, :pnl, :fmv,
                        :currency, :calVer, :vHash, NOW())
                ON CONFLICT (tenant_id, delivery_point_id, portfolio_id,
                             interval_start, granularity, is_peak)
                DO UPDATE SET
                    net_mw = EXCLUDED.net_mw,
                    net_mwh = EXCLUDED.net_mwh,
                    settled_value = EXCLUDED.settled_value,
                    market_value = EXCLUDED.market_value,
                    pnl = EXCLUDED.pnl,
                    forward_mark_value = EXCLUDED.forward_mark_value,
                    version_hash = EXCLUDED.version_hash,
                    refreshed_at = EXCLUDED.refreshed_at
                """)
                .setParameter("tenantId", tenantId)
                .setParameter("dpId", cell.deliveryPointId())
                .setParameter("portId", cell.portfolioId())
                .setParameter("start", cell.periodStart())
                .setParameter("end", cell.periodEnd())
                .setParameter("granularity", cell.granularity().name())
                .setParameter("netMw", cell.netMw())
                .setParameter("netMwh", cell.netMwh())
                .setParameter("isPeak", cell.isPeak())
                .setParameter("settledValue", cell.settledValue())
                .setParameter("marketValue", cell.marketValue())
                .setParameter("pnl", cell.pnl())
                .setParameter("fmv", cell.forwardMarkValue())
                .setParameter("currency", cell.currency())
                .setParameter("calVer", cell.calendarVersion())
                .setParameter("vHash", cell.versionHash())
                .executeUpdate();
        }
    }

    private RollupCell mapToRollupCell(Object[] row) {
        return new RollupCell(
            ((java.sql.Timestamp) row[3]).toInstant(),          // periodStart
            ((java.sql.Timestamp) row[4]).toInstant(),          // periodEnd
            TimeGranularity.valueOf((String) row[5]),           // granularity
            (String) row[1],                                    // deliveryPointId
            (String) row[2],                                    // portfolioId
            (Boolean) row[8],                                   // isPeak
            (BigDecimal) row[6],                                // netMw
            (BigDecimal) row[7],                                // netMwh
            toBigDecimal(row[9]),                               // settledValue
            toBigDecimal(row[10]),                              // marketValue
            toBigDecimal(row[11]),                              // pnl
            toBigDecimal(row[12]),                              // forwardMarkValue
            row[15] != null ? (String) row[15] : "EUR",        // currency
            (String) row[13],                                   // calendarVersion
            (String) row[14]                                    // versionHash
        );
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
}
