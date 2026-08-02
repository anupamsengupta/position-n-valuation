package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.persistence.entity.*;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/**
 * JPA adapter for {@link MarketDataRepository}. Pattern #18, S4.
 */
public class JpaMarketDataRepository implements MarketDataRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaMarketDataRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    // --- reads ---

    @Override
    public Optional<MarketDataLookup> findFixing(String tenantId, String series,
                                                   Instant intervalStart) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM FixingEntity e
                WHERE e.tenantId = :tenantId
                  AND e.series = :series
                  AND e.intervalStart = :intervalStart
                ORDER BY e.versionId DESC
                """, FixingEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("series", series)
            .setParameter("intervalStart", intervalStart)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toFixingLookup(results.get(0)));
    }

    @Override
    public Optional<MarketDataLookup> findIndex(String tenantId, String series,
                                                  String refMonthExpression) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM IndexValueEntity e
                WHERE e.tenantId = :tenantId
                  AND e.series = :series
                  AND e.refMonthExpression = :refMonthExpression
                ORDER BY e.versionId DESC
                """, IndexValueEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("series", series)
            .setParameter("refMonthExpression", refMonthExpression)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toIndexLookup(results.get(0)));
    }

    @Override
    public Optional<MarketDataLookup> findForwardCurve(String tenantId, String series,
                                                         YearMonth pillar, Instant asOfDate) {
        // Floor semantics: as_of_date <= asOfDate, latest version
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM ForwardCurveEntity e
                WHERE e.tenantId = :tenantId
                  AND e.series = :series
                  AND e.pillar = :pillar
                  AND e.asOfDate <= :asOfDate
                ORDER BY e.asOfDate DESC, e.versionId DESC
                """, ForwardCurveEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("series", series)
            .setParameter("pillar", pillar.toString())
            .setParameter("asOfDate", asOfDate)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toCurveLookup(results.get(0)));
    }

    @Override
    public Optional<MarketDataLookup> findFxRate(String tenantId, String currencyPair,
                                                    Instant referenceDate) {
        // Floor semantics: reference_date <= referenceDate, latest version
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM FxRateEntity e
                WHERE e.tenantId = :tenantId
                  AND e.currencyPair = :currencyPair
                  AND e.referenceDate <= :referenceDate
                ORDER BY e.referenceDate DESC, e.versionId DESC
                """, FxRateEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("currencyPair", currencyPair)
            .setParameter("referenceDate", referenceDate)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toFxLookup(results.get(0)));
    }

    @Override
    public Optional<MarketDataLookup> findSpread(String tenantId, String series,
                                                    Instant intervalStart) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM SpreadEntity e
                WHERE e.tenantId = :tenantId
                  AND e.series = :series
                  AND e.intervalStart = :intervalStart
                ORDER BY e.versionId DESC
                """, SpreadEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("series", series)
            .setParameter("intervalStart", intervalStart)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toSpreadLookup(results.get(0)));
    }

    @Override
    public Optional<VolSurfaceLookup> findVolSurface(String tenantId, String surfaceId,
                                                       double strikeDelta, String expiryTenor,
                                                       Instant asOfDate) {
        // Floor semantics on as_of_date, exact match on strike_delta + expiry_tenor
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM VolSurfaceEntity e
                WHERE e.tenantId = :tenantId
                  AND e.surfaceId = :surfaceId
                  AND e.strikeDelta = :strikeDelta
                  AND e.expiryTenor = :expiryTenor
                  AND e.asOfDate <= :asOfDate
                ORDER BY e.asOfDate DESC, e.versionId DESC
                """, VolSurfaceEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("surfaceId", surfaceId)
            .setParameter("strikeDelta", strikeDelta)
            .setParameter("expiryTenor", expiryTenor)
            .setParameter("asOfDate", asOfDate)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toVolSurfaceLookup(results.get(0)));
    }

    @Override
    public Optional<MarketDataLookup> findAtVersion(String tenantId, String series,
                                                       Instant intervalStart, long versionId) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM FixingEntity e
                WHERE e.tenantId = :tenantId
                  AND e.series = :series
                  AND e.intervalStart = :intervalStart
                  AND e.versionId = :versionId
                """, FixingEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("series", series)
            .setParameter("intervalStart", intervalStart)
            .setParameter("versionId", versionId)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty()
            : Optional.of(toFixingLookup(results.get(0)));
    }

    // --- writes ---

    @Override
    public void saveFixing(String tenantId, String series, Instant intervalStart,
                           MarketDataLookup lookup) {
        var e = new FixingEntity();
        e.setTenantId(tenantId);
        e.setSeries(series);
        e.setIntervalStart(intervalStart);
        e.setValue(lookup.value());
        e.setVersionId(lookup.versionId());
        e.setQualityState(lookup.qualityState().name());
        e.setCreatedAt(Instant.now());
        emProvider.get().persist(e);
    }

    @Override
    public void saveForwardCurve(String tenantId, String series, YearMonth pillar,
                                  Instant asOfDate, MarketDataLookup lookup) {
        var e = new ForwardCurveEntity();
        e.setTenantId(tenantId);
        e.setSeries(series);
        e.setPillar(pillar.toString());
        e.setAsOfDate(asOfDate);
        e.setValue(lookup.value());
        e.setVersionId(lookup.versionId());
        e.setQualityState(lookup.qualityState().name());
        e.setCreatedAt(Instant.now());
        emProvider.get().persist(e);
    }

    @Override
    public void saveFxRate(String tenantId, String currencyPair, Instant referenceDate,
                           MarketDataLookup lookup) {
        var e = new FxRateEntity();
        e.setTenantId(tenantId);
        e.setCurrencyPair(currencyPair);
        e.setReferenceDate(referenceDate);
        e.setRate(lookup.value());
        e.setVersionId(lookup.versionId());
        e.setQualityState(lookup.qualityState().name());
        e.setCreatedAt(Instant.now());
        emProvider.get().persist(e);
    }

    @Override
    public void saveIndex(String tenantId, String series, String refMonthExpression,
                          MarketDataLookup lookup) {
        var e = new IndexValueEntity();
        e.setTenantId(tenantId);
        e.setSeries(series);
        e.setRefMonthExpression(refMonthExpression);
        e.setValue(lookup.value());
        e.setVersionId(lookup.versionId());
        e.setQualityState(lookup.qualityState().name());
        e.setCreatedAt(Instant.now());
        emProvider.get().persist(e);
    }

    @Override
    public void saveSpread(String tenantId, String series, Instant intervalStart,
                           MarketDataLookup lookup) {
        var e = new SpreadEntity();
        e.setTenantId(tenantId);
        e.setSeries(series);
        e.setIntervalStart(intervalStart);
        e.setValue(lookup.value());
        e.setVersionId(lookup.versionId());
        e.setQualityState(lookup.qualityState().name());
        e.setCreatedAt(Instant.now());
        emProvider.get().persist(e);
    }

    @Override
    public void saveVolSurface(String tenantId, String surfaceId, double strikeDelta,
                               String expiryTenor, Instant asOfDate, VolSurfaceLookup lookup) {
        var e = new VolSurfaceEntity();
        e.setTenantId(tenantId);
        e.setSurfaceId(surfaceId);
        e.setStrikeDelta(strikeDelta);
        e.setExpiryTenor(expiryTenor);
        e.setAsOfDate(asOfDate);
        e.setImpliedVolatility(lookup.impliedVolatility());
        e.setVersionId(lookup.versionId());
        e.setQualityState(lookup.qualityState().name());
        e.setCreatedAt(Instant.now());
        emProvider.get().persist(e);
    }

    // --- mappers ---

    private MarketDataLookup toFixingLookup(FixingEntity e) {
        return new MarketDataLookup(e.getValue(), e.getVersionId(), e.getSeries(),
            e.getIntervalStart(), QualityState.valueOf(e.getQualityState()));
    }

    private MarketDataLookup toIndexLookup(IndexValueEntity e) {
        return new MarketDataLookup(e.getValue(), e.getVersionId(), e.getSeries(),
            e.getCreatedAt(), QualityState.valueOf(e.getQualityState()));
    }

    private MarketDataLookup toCurveLookup(ForwardCurveEntity e) {
        return new MarketDataLookup(e.getValue(), e.getVersionId(), e.getSeries(),
            e.getAsOfDate(), QualityState.valueOf(e.getQualityState()));
    }

    private MarketDataLookup toFxLookup(FxRateEntity e) {
        return new MarketDataLookup(e.getRate(), e.getVersionId(), e.getCurrencyPair(),
            e.getReferenceDate(), QualityState.valueOf(e.getQualityState()));
    }

    private MarketDataLookup toSpreadLookup(SpreadEntity e) {
        return new MarketDataLookup(e.getValue(), e.getVersionId(), e.getSeries(),
            e.getIntervalStart(), QualityState.valueOf(e.getQualityState()));
    }

    private VolSurfaceLookup toVolSurfaceLookup(VolSurfaceEntity e) {
        return new VolSurfaceLookup(e.getImpliedVolatility(), e.getVersionId(),
            e.getSurfaceId(), e.getStrikeDelta(), e.getExpiryTenor(),
            e.getAsOfDate(), QualityState.valueOf(e.getQualityState()));
    }
}
