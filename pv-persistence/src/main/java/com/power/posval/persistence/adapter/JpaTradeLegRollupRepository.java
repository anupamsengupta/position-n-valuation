package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.TradeLegRollupCell;
import com.power.posval.domain.port.repository.TradeLegRollupRepository;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA adapter for {@link TradeLegRollupRepository}.
 *
 * <p>Uses native SQL with {@code Provider<EntityManager>} injection, following
 * the same pattern as {@link JpaRollupRepository}. All queries filter by
 * {@code tenant_id} per D-14 and Pattern #32.
 *
 * <p>The {@code saveAll} method uses {@code INSERT ... ON CONFLICT DO UPDATE}
 * with conflict target {@code (tenant_id, position_id, period_start, granularity)},
 * matching the unique constraint {@code uq_tlr_position_period_gran} (S7.2).
 *
 * <p>Pattern #18 (Repository Port + Adapter). S6.1, S7, FR-035, D-13, D-14.
 */
public class JpaTradeLegRollupRepository implements TradeLegRollupRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaTradeLegRollupRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Q-10: Trade-leg rollup cells for a portfolio within a delivery range.
     *
     * <p>Uses index {@code idx_tlr_portfolio_granularity_time} on
     * {@code (tenant_id, portfolio_id, granularity, period_start)}.
     *
     * <p>Pattern #18, #32. S7, FR-035.
     */
    @Override
    public List<TradeLegRollupCell> findByPortfolio(String tenantId,
                                                     String portfolioId,
                                                     Instant rangeStart,
                                                     Instant rangeEnd,
                                                     TimeGranularity granularity) {
        return emProvider.get()
                .createNativeQuery("""
                        SELECT position_id, tenant_id, trade_id, trade_leg_id, trade_version,
                               delivery_point_id, portfolio_id,
                               period_start, period_end, granularity,
                               settled_mw, settled_mwh, avg_price, settled_value, market_value,
                               realized_pnl, has_forward_intervals, delivery_status,
                               quantity, volume_unit, currency, version_hash, refreshed_at
                        FROM volume_series.trade_leg_rollup_cell
                        WHERE tenant_id = :tenantId
                          AND portfolio_id = :portfolioId
                          AND period_start < :rangeEnd
                          AND period_end > :rangeStart
                          AND granularity = :granularity
                        ORDER BY period_start, trade_leg_id
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("portfolioId", portfolioId)
                .setParameter("rangeStart", rangeStart)
                .setParameter("rangeEnd", rangeEnd)
                .setParameter("granularity", granularity.name())
                .getResultList()
                .stream()
                .map(row -> mapToRollupCell((Object[]) row))
                .toList();
    }

    /**
     * Upsert trade-leg rollup cells.
     *
     * <p>Conflict target: {@code (tenant_id, position_id, period_start, granularity)}.
     * Idempotent: re-processing the same {@code SettlementComputed} event produces
     * identical rows (S8.3).
     *
     * <p>Pattern #18. S7.
     */
    @Override
    public void saveAll(String tenantId, List<TradeLegRollupCell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        var em = emProvider.get();
        for (TradeLegRollupCell cell : cells) {
            em.createNativeQuery("""
                    INSERT INTO volume_series.trade_leg_rollup_cell
                      (position_id, tenant_id, trade_id, trade_leg_id, trade_version,
                       delivery_point_id, portfolio_id,
                       period_start, period_end, granularity,
                       settled_mw, settled_mwh, avg_price, settled_value, market_value,
                       realized_pnl, has_forward_intervals, delivery_status,
                       quantity, volume_unit, currency, version_hash, refreshed_at)
                    VALUES (:positionId, :tenantId, :tradeId, :tradeLegId, :tradeVersion,
                            :deliveryPointId, :portfolioId,
                            :periodStart, :periodEnd, :granularity,
                            :settledMw, :settledMwh, :avgPrice, :settledValue, :marketValue,
                            :realizedPnl, :hasForwardIntervals, :deliveryStatus,
                            :quantity, :volumeUnit, :currency, :versionHash, :refreshedAt)
                    ON CONFLICT (tenant_id, position_id, period_start, granularity)
                    DO UPDATE SET
                        trade_id             = EXCLUDED.trade_id,
                        trade_leg_id         = EXCLUDED.trade_leg_id,
                        trade_version        = EXCLUDED.trade_version,
                        delivery_point_id    = EXCLUDED.delivery_point_id,
                        portfolio_id         = EXCLUDED.portfolio_id,
                        period_end           = EXCLUDED.period_end,
                        settled_mw           = EXCLUDED.settled_mw,
                        settled_mwh          = EXCLUDED.settled_mwh,
                        avg_price            = EXCLUDED.avg_price,
                        settled_value        = EXCLUDED.settled_value,
                        market_value         = EXCLUDED.market_value,
                        realized_pnl         = EXCLUDED.realized_pnl,
                        has_forward_intervals = EXCLUDED.has_forward_intervals,
                        delivery_status      = EXCLUDED.delivery_status,
                        quantity             = EXCLUDED.quantity,
                        volume_unit          = EXCLUDED.volume_unit,
                        currency             = EXCLUDED.currency,
                        version_hash         = EXCLUDED.version_hash,
                        refreshed_at         = EXCLUDED.refreshed_at
                    """)
                    .setParameter("positionId", cell.positionId())
                    .setParameter("tenantId", tenantId)
                    .setParameter("tradeId", cell.tradeId())
                    .setParameter("tradeLegId", cell.tradeLegId())
                    .setParameter("tradeVersion", cell.tradeVersion())
                    .setParameter("deliveryPointId", cell.deliveryPointId())
                    .setParameter("portfolioId", cell.portfolioId())
                    .setParameter("periodStart", cell.periodStart())
                    .setParameter("periodEnd", cell.periodEnd())
                    .setParameter("granularity", cell.granularity().name())
                    .setParameter("settledMw", cell.settledMw())
                    .setParameter("settledMwh", cell.settledMwh())
                    .setParameter("avgPrice", cell.avgPrice())
                    .setParameter("settledValue", cell.settledValue())
                    .setParameter("marketValue", cell.marketValue())
                    .setParameter("realizedPnl", cell.realizedPnl())
                    .setParameter("hasForwardIntervals", cell.hasForwardIntervals())
                    .setParameter("deliveryStatus", cell.deliveryStatus())
                    .setParameter("quantity", cell.quantity())
                    .setParameter("volumeUnit", cell.volumeUnit())
                    .setParameter("currency", cell.currency())
                    .setParameter("versionHash", cell.versionHash())
                    .setParameter("refreshedAt", cell.refreshedAt())
                    .executeUpdate();
        }
    }

    /**
     * Delete stale rollups for a superseded position (S8.4, Option A).
     *
     * <p>Pattern #18. S7.
     */
    @Override
    public void deleteByPositionId(String tenantId, UUID positionId) {
        emProvider.get()
                .createNativeQuery("""
                        DELETE FROM volume_series.trade_leg_rollup_cell
                        WHERE tenant_id = :tenantId
                          AND position_id = :positionId
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("positionId", positionId)
                .executeUpdate();
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    /**
     * Maps a native-query result row to {@link TradeLegRollupCell}.
     *
     * <p>Column order matches the SELECT list in {@link #findByPortfolio}:
     * position_id[0], tenant_id[1], trade_id[2], trade_leg_id[3], trade_version[4],
     * delivery_point_id[5], portfolio_id[6], period_start[7], period_end[8],
     * granularity[9], settled_mw[10], settled_mwh[11], avg_price[12],
     * settled_value[13], market_value[14], realized_pnl[15],
     * has_forward_intervals[16], delivery_status[17], quantity[18],
     * volume_unit[19], currency[20], version_hash[21], refreshed_at[22].
     */
    private static TradeLegRollupCell mapToRollupCell(Object[] row) {
        return new TradeLegRollupCell(
                toUuid(row[0]),                                   // positionId
                (String) row[1],                                  // tenantId
                (String) row[2],                                  // tradeId
                (String) row[3],                                  // tradeLegId
                toInt(row[4]),                                    // tradeVersion
                (String) row[5],                                  // deliveryPointId
                (String) row[6],                                  // portfolioId
                toInstant(row[7]),                                // periodStart
                toInstant(row[8]),                                // periodEnd
                TimeGranularity.valueOf((String) row[9]),         // granularity
                toBigDecimal(row[10]),                            // settledMw
                toBigDecimal(row[11]),                            // settledMwh
                toBigDecimal(row[12]),                            // avgPrice
                toBigDecimal(row[13]),                            // settledValue
                toBigDecimal(row[14]),                            // marketValue
                toBigDecimal(row[15]),                            // realizedPnl
                toBoolean(row[16]),                               // hasForwardIntervals
                (String) row[17],                                 // deliveryStatus
                toBigDecimal(row[18]),                            // quantity
                (String) row[19],                                 // volumeUnit
                row[20] != null ? (String) row[20] : "EUR",      // currency
                (String) row[21],                                 // versionHash
                toInstant(row[22])                                // refreshedAt
        );
    }

    private static UUID toUuid(Object v) {
        if (v instanceof UUID u) return u;
        if (v instanceof String s) return UUID.fromString(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to UUID");
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Instant");
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
