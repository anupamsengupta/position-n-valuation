package com.power.posval.persistence.adapter;

import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.entity.TradeIntervalCacheEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA adapter for TradeIntervalCache (S6b). §12, Pattern #18.
 */
public class JpaTradeIntervalCache implements TradeIntervalCache {

    private final Provider<EntityManager> emProvider;
    private final BatchWriter batchWriter;

    @Inject
    public JpaTradeIntervalCache(Provider<EntityManager> emProvider,
                                  BatchWriter batchWriter) {
        this.emProvider = emProvider;
        this.batchWriter = batchWriter;
    }

    @Override
    public List<TradeIntervalRecord> getForTradeLeg(String tenantId,
                                                      String tradeLegId,
                                                      Instant rangeStart,
                                                      Instant rangeEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM TradeIntervalCacheEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.tradeLegId = :tradeLegId
                  AND e.intervalStart < :rangeEnd
                  AND e.intervalEnd > :rangeStart
                ORDER BY e.intervalStart
                """, TradeIntervalCacheEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeLegId", tradeLegId)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public void rebuild(String tenantId, String tradeLegId, Instant rangeStart, Instant rangeEnd) {
        EntityManager em = emProvider.get();

        // Delete existing cache entries for affected range
        em.createQuery("""
            DELETE FROM TradeIntervalCacheEntity e
            WHERE e.tenantId   = :tenantId
              AND e.tradeLegId = :tradeLegId
              AND e.intervalStart < :end
              AND e.intervalEnd > :start
            """)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeLegId", tradeLegId)
            .setParameter("start", rangeStart)
            .setParameter("end", rangeEnd)
            .executeUpdate();
    }

    @Override
    public void writeAll(String tenantId, List<TradeIntervalRecord> records) {
        Instant now = Instant.now();
        List<TradeIntervalCacheEntity> entities = records.stream().map(r -> {
            var entity = new TradeIntervalCacheEntity();
            entity.setTenantId(tenantId);
            entity.setTradeLegId(r.tradeLegId());
            entity.setIntervalStart(r.intervalStart());
            entity.setIntervalEnd(r.intervalEnd());
            entity.setResolvedQty(r.resolvedQty());
            entity.setResolvedEnergy(r.resolvedEnergy());
            entity.setMultiplier(r.multiplier());
            entity.setSeriesKey(r.seriesKey());
            entity.setVersionHash(r.versionHash());
            entity.setCreatedAt(now);
            return entity;
        }).toList();
        batchWriter.writeAll(entities);
    }

    /**
     * Q-7: Bulk-fetch S6b records for multiple trade-legs within an interval range.
     * Batches the IN clause into chunks of 100 to avoid PostgreSQL parameter limits.
     * Uses existing index {@code idx_tic_trade_leg_time}.
     * Pattern #18, §6.3.
     */
    @Override
    public List<TradeIntervalRecord> getForTradeLegIds(String tenantId,
                                                        List<String> tradeLegIds,
                                                        Instant rangeStart,
                                                        Instant rangeEnd) {
        if (tradeLegIds == null || tradeLegIds.isEmpty()) {
            return List.of();
        }

        List<TradeIntervalRecord> result = new ArrayList<>();
        // Batch into chunks of 100 to avoid PostgreSQL IN clause parameter limits
        int chunkSize = 100;
        for (int i = 0; i < tradeLegIds.size(); i += chunkSize) {
            List<String> chunk = tradeLegIds.subList(i, Math.min(i + chunkSize, tradeLegIds.size()));
            List<TradeIntervalRecord> chunkResult = emProvider.get()
                .createQuery("""
                    SELECT e FROM TradeIntervalCacheEntity e
                    WHERE e.tenantId   = :tenantId
                      AND e.tradeLegId IN :tradeLegIds
                      AND e.intervalStart < :rangeEnd
                      AND e.intervalEnd > :rangeStart
                    ORDER BY e.tradeLegId, e.intervalStart
                    """, TradeIntervalCacheEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("tradeLegIds", chunk)
                .setParameter("rangeStart", rangeStart)
                .setParameter("rangeEnd", rangeEnd)
                .getResultStream()
                .map(this::toDomain)
                .toList();
            result.addAll(chunkResult);
        }
        return result;
    }

    private TradeIntervalRecord toDomain(TradeIntervalCacheEntity e) {
        return new TradeIntervalRecord(
            e.getTradeLegId(),
            e.getIntervalStart(),
            e.getIntervalEnd(),
            e.getResolvedQty(),
            e.getResolvedEnergy(),
            e.getMultiplier(),
            e.getSeriesKey(),
            e.getVersionHash());
    }
}
