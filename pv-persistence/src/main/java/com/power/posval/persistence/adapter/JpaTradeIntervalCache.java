package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.persistence.entity.TradeIntervalCacheEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;

/**
 * JPA adapter for TradeIntervalCache (S6b). §12, Pattern #18.
 */
public class JpaTradeIntervalCache implements TradeIntervalCache {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaTradeIntervalCache(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
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
    public void rebuild(String tenantId, String tradeLegId, DeliveryRange affectedRange) {
        EntityManager em = emProvider.get();
        Instant start = affectedRange.startInstant().toInstant();
        Instant end = affectedRange.endInstant().toInstant();

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
            .setParameter("start", start)
            .setParameter("end", end)
            .executeUpdate();
    }

    @Override
    public void writeAll(String tenantId, List<TradeIntervalRecord> records) {
        EntityManager em = emProvider.get();
        Instant now = Instant.now();
        for (TradeIntervalRecord r : records) {
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
            em.persist(entity);
        }
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
