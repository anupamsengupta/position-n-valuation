package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

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
        // Rollup queries go against materialized rollup views.
        // The view schema is deployment-specific (DDL in Flyway).
        // Return from native query mapping.
        return emProvider.get()
            .createNativeQuery("""
                SELECT tenant_id, delivery_point_id, portfolio_id,
                       interval_start, interval_end, granularity,
                       net_mw, net_mwh, is_peak, trade_count,
                       calendar_version, version_hash, refreshed_at
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

    private RollupCell mapToRollupCell(Object[] row) {
        return new RollupCell(
            ((java.sql.Timestamp) row[3]).toInstant(),          // periodStart
            ((java.sql.Timestamp) row[4]).toInstant(),          // periodEnd
            TimeGranularity.valueOf((String) row[5]),           // granularity
            (String) row[1],                                    // deliveryPointId
            (String) row[2],                                    // portfolioId
            (Boolean) row[8],                                   // isPeak
            (java.math.BigDecimal) row[6],                      // netMw
            (java.math.BigDecimal) row[7],                      // netMwh
            java.math.BigDecimal.ZERO,                          // settledValue
            java.math.BigDecimal.ZERO,                          // forwardMarkValue
            "EUR",                                              // currency
            (String) row[10],                                   // calendarVersion
            (String) row[11]                                    // versionHash
        );
    }
}
