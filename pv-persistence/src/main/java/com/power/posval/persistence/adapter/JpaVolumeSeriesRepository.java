package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.entity.VolumeIntervalEntity;
import com.power.posval.persistence.entity.VolumeSeriesEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * JPA adapter for VolumeSeriesRepository. §9.3, Pattern #18.
 */
public class JpaVolumeSeriesRepository implements VolumeSeriesRepository {

    private final Provider<EntityManager> emProvider;
    private final BatchWriter batchWriter;

    @Inject
    public JpaVolumeSeriesRepository(Provider<EntityManager> emProvider,
                                      BatchWriter batchWriter) {
        this.emProvider = emProvider;
        this.batchWriter = batchWriter;
    }

    @Override
    public void save(VolumeSeries series) {
        EntityManager em = emProvider.get();
        VolumeSeriesEntity entity = toEntity(series);
        em.persist(entity);

        List<VolumeIntervalEntity> intervalEntities = series.intervals().stream()
            .map(vi -> toIntervalEntity(vi, entity))
            .toList();
        batchWriter.writeAll(intervalEntities);
    }

    @Override
    public Optional<VolumeSeries> findById(UUID id) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM VolumeSeriesEntity e
                LEFT JOIN FETCH e.intervals
                WHERE e.seriesUuid = :uuid
                """, VolumeSeriesEntity.class)
            .setParameter("uuid", id)
            .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String seriesKey) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM VolumeSeriesEntity e
                LEFT JOIN FETCH e.intervals
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
    public Optional<VolumeSeries> findCurrentBySeriesKeyAndRange(String tenantId, String seriesKey,
                                                                   Instant rangeStart, Instant rangeEnd) {
        EntityManager em = emProvider.get();

        // Query 1: series metadata only (no interval join)
        var seriesResults = em.createQuery("""
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

        if (seriesResults.isEmpty()) {
            return Optional.empty();
        }

        VolumeSeriesEntity seriesEntity = seriesResults.get(0);

        // Query 2: only intervals overlapping [rangeStart, rangeEnd)
        List<VolumeIntervalEntity> rangeIntervals = em.createQuery("""
                SELECT vi FROM VolumeIntervalEntity vi
                WHERE vi.series = :series
                  AND vi.intervalStart < :rangeEnd
                  AND vi.intervalEnd > :rangeStart
                ORDER BY vi.intervalStart ASC
                """, VolumeIntervalEntity.class)
            .setParameter("series", seriesEntity)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd)
            .getResultList();

        // Build domain object directly from range-filtered intervals
        // (do NOT mutate seriesEntity.intervals — Hibernate tracks that collection)
        return Optional.of(toDomainWithIntervals(seriesEntity, rangeIntervals));
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
        EntityManager em = emProvider.get();
        em.createQuery("""
            UPDATE VolumeSeriesEntity e
            SET e.qualityState = 'SUPERSEDED'
            WHERE e.seriesUuid = :uuid
            """)
            .setParameter("uuid", oldVersion.id())
            .executeUpdate();
        save(newVersion);
    }

    private VolumeSeriesEntity toEntity(VolumeSeries s) {
        var e = new VolumeSeriesEntity();
        e.setSeriesUuid(s.id());
        e.setTenantId("default");
        e.setSeriesKey(s.seriesKey().value());
        e.setSeriesType(s.seriesType().name());
        e.setAssetId(s.assetId());
        e.setTradeLegId(s.tradeLegId());
        e.setVersionId(s.versionId());
        e.setTimeGranularity(s.granularity().name());
        e.setQualityState(s.qualityState().name());
        e.setMaterializationStatus(s.materializationStatus().name());
        e.setTransactionTime(s.transactionTime());
        e.setValidTime(s.validTime());
        if (s.deliveryPeriod() != null) {
            e.setDeliveryStart(s.deliveryPeriod().start().toInstant());
            e.setDeliveryEnd(s.deliveryPeriod().end().toInstant());
            e.setDeliveryTimezone(s.deliveryPeriod().deliveryTimezone().getId());
        }
        return e;
    }

    private VolumeIntervalEntity toIntervalEntity(VolumeInterval vi, VolumeSeriesEntity parent) {
        var e = new VolumeIntervalEntity();
        e.setIntervalUuid(vi.id());
        e.setSeries(parent);
        e.setTenantId(parent.getTenantId());
        e.setIntervalStart(vi.intervalStart());
        e.setIntervalEnd(vi.intervalEnd());
        e.setVolume(vi.volume());
        e.setEnergy(vi.energy());
        e.setVersion(vi.version());
        e.setSupersedesId(vi.supersedesId());
        return e;
    }

    private VolumeSeries toDomain(VolumeSeriesEntity e) {
        List<VolumeInterval> intervals = e.getIntervals().stream()
            .map(vi -> (VolumeInterval) new DefaultVolumeInterval(
                vi.getIntervalUuid(),
                vi.getIntervalStart(),
                vi.getIntervalEnd(),
                vi.getVolume(),
                vi.getEnergy(),
                vi.getVersion(),
                vi.getSupersedesId()))
            .toList();

        ZoneId tz = e.getDeliveryTimezone() != null
            ? ZoneId.of(e.getDeliveryTimezone()) : ZoneId.of("Europe/Berlin");
        Instant txTime = e.getTransactionTime() != null ? e.getTransactionTime() : Instant.now();

        DeliveryPeriod dp;
        if (e.getDeliveryStart() != null && e.getDeliveryEnd() != null) {
            dp = new DeliveryPeriod(
                ZonedDateTime.ofInstant(e.getDeliveryStart(), tz),
                ZonedDateTime.ofInstant(e.getDeliveryEnd(), tz), tz);
        } else {
            dp = new DeliveryPeriod(
                ZonedDateTime.now(tz), ZonedDateTime.now(tz).plusMonths(1), tz);
        }

        return DefaultVolumeSeries.builder()
            .id(e.getSeriesUuid())
            .seriesKey(new SeriesKey(e.getSeriesKey()))
            .seriesType(SeriesType.valueOf(e.getSeriesType()))
            .assetId(e.getAssetId())
            .tradeLegId(e.getTradeLegId())
            .versionId(e.getVersionId())
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.valueOf(e.getTimeGranularity()))
            .deliveryPeriod(dp)
            .qualityState(QualityState.valueOf(e.getQualityState()))
            .materializationStatus(MaterializationStatus.valueOf(e.getMaterializationStatus()))
            .transactionTime(txTime)
            .validTime(e.getValidTime())
            .intervals(intervals)
            .build();
    }

    private VolumeSeries toDomainWithIntervals(VolumeSeriesEntity e,
                                                List<VolumeIntervalEntity> intervalEntities) {
        List<VolumeInterval> intervals = intervalEntities.stream()
            .map(vi -> (VolumeInterval) new DefaultVolumeInterval(
                vi.getIntervalUuid(),
                vi.getIntervalStart(),
                vi.getIntervalEnd(),
                vi.getVolume(),
                vi.getEnergy(),
                vi.getVersion(),
                vi.getSupersedesId()))
            .toList();

        ZoneId tz = e.getDeliveryTimezone() != null
            ? ZoneId.of(e.getDeliveryTimezone()) : ZoneId.of("Europe/Berlin");
        Instant txTime = e.getTransactionTime() != null ? e.getTransactionTime() : Instant.now();

        DeliveryPeriod dp;
        if (e.getDeliveryStart() != null && e.getDeliveryEnd() != null) {
            dp = new DeliveryPeriod(
                ZonedDateTime.ofInstant(e.getDeliveryStart(), tz),
                ZonedDateTime.ofInstant(e.getDeliveryEnd(), tz), tz);
        } else {
            dp = new DeliveryPeriod(
                ZonedDateTime.now(tz), ZonedDateTime.now(tz).plusMonths(1), tz);
        }

        return DefaultVolumeSeries.builder()
            .id(e.getSeriesUuid())
            .seriesKey(new SeriesKey(e.getSeriesKey()))
            .seriesType(SeriesType.valueOf(e.getSeriesType()))
            .assetId(e.getAssetId())
            .tradeLegId(e.getTradeLegId())
            .versionId(e.getVersionId())
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.valueOf(e.getTimeGranularity()))
            .deliveryPeriod(dp)
            .qualityState(QualityState.valueOf(e.getQualityState()))
            .materializationStatus(MaterializationStatus.valueOf(e.getMaterializationStatus()))
            .transactionTime(txTime)
            .validTime(e.getValidTime())
            .intervals(intervals)
            .build();
    }
}
