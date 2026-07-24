package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.persistence.entity.VolumeSeriesEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for VolumeSeriesRepository. §9.3, Pattern #18.
 * VolumeSeriesSpec → CriteriaQuery translation.
 */
public class JpaVolumeSeriesRepository implements VolumeSeriesRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaVolumeSeriesRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(VolumeSeries series) {
        // Skeleton — full entity mapping deferred to when VolumeSeries
        // domain model has full field accessors
        throw new UnsupportedOperationException("save not yet implemented");
    }

    @Override
    public Optional<VolumeSeries> findById(UUID id) {
        throw new UnsupportedOperationException("findById not yet implemented");
    }

    @Override
    public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String seriesKey) {
        // JPQL: WHERE tenant_id = :tid AND series_key = :sk
        //       AND quality_state IN ('CURRENT', 'EFFECTIVE')
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM VolumeSeriesEntity e
                WHERE e.tenantId  = :tenantId
                  AND e.seriesKey = :seriesKey
                  AND e.qualityState IN ('CURRENT', 'EFFECTIVE')
                ORDER BY e.versionId DESC
                """, VolumeSeriesEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("seriesKey", seriesKey)
            .setMaxResults(1)
            .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<VolumeSeries> findByTenantId(String tenantId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM VolumeSeriesEntity e
                WHERE e.tenantId = :tenantId
                ORDER BY e.seriesKey, e.versionId DESC
                """, VolumeSeriesEntity.class)
            .setParameter("tenantId", tenantId)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<VolumeSeries> findAll(String tenantId, VolumeSeriesSpec spec) {
        EntityManager em = emProvider.get();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<VolumeSeriesEntity> cq = cb.createQuery(VolumeSeriesEntity.class);
        Root<VolumeSeriesEntity> root = cq.from(VolumeSeriesEntity.class);

        Predicate tenantPredicate = cb.equal(root.get("tenantId"), tenantId);
        Predicate specPredicate = spec.toPredicate(root, cq, cb);

        cq.where(cb.and(tenantPredicate, specPredicate));
        cq.orderBy(cb.asc(root.get("seriesKey")));

        return em.createQuery(cq)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public boolean existsByTradeIdAndTradeVersion(String tradeId, int tradeVersion) {
        Long count = emProvider.get()
            .createQuery("""
                SELECT COUNT(e) FROM VolumeSeriesEntity e
                WHERE e.tradeLegId = :tradeId
                  AND e.versionId  = :version
                """, Long.class)
            .setParameter("tradeId", tradeId)
            .setParameter("version", (long) tradeVersion)
            .getSingleResult();
        return count > 0;
    }

    @Override
    public void supersede(VolumeSeries oldVersion, VolumeSeries newVersion) {
        // Mark old as SUPERSEDED, persist new
        throw new UnsupportedOperationException("supersede not yet implemented");
    }

    private VolumeSeries toDomain(VolumeSeriesEntity entity) {
        // Skeleton — returns null until VolumeSeries domain model
        // supports full construction from entity fields
        throw new UnsupportedOperationException("toDomain not yet implemented");
    }
}
