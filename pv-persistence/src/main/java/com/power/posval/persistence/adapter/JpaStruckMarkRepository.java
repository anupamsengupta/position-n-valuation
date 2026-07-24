package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.StruckMark;
import com.power.posval.domain.port.repository.StruckMarkRepository;
import com.power.posval.persistence.entity.StruckMarkEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for StruckMarkRepository. §11.3, Pattern #18.
 */
public class JpaStruckMarkRepository implements StruckMarkRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaStruckMarkRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(StruckMark mark) {
        emProvider.get().persist(toEntity(mark));
    }

    @Override
    public Optional<StruckMark> findLatest(String tenantId, UUID positionId,
                                             YearMonth deliveryMonth) {
        var results = emProvider.get()
            .createQuery("""
                SELECT e FROM StruckMarkEntity e
                WHERE e.tenantId = :tenantId
                  AND e.positionId = :positionId
                  AND e.deliveryMonth = :deliveryMonth
                ORDER BY e.createdAt DESC
                """, StruckMarkEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("positionId", positionId)
            .setParameter("deliveryMonth", deliveryMonth.toString())
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<StruckMark> findByStrikeDate(String tenantId, LocalDate strikeDate) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM StruckMarkEntity e
                WHERE e.tenantId = :tenantId
                  AND e.strikeDate = :strikeDate
                ORDER BY e.positionId
                """, StruckMarkEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("strikeDate", strikeDate)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    private StruckMarkEntity toEntity(StruckMark m) {
        var e = new StruckMarkEntity();
        e.setTenantId(m.tenantId());
        e.setPositionId(m.positionId());
        e.setDeliveryMonth(m.deliveryMonth().toString());
        e.setStrikeDate(m.strikeDate());
        e.setMarkValue(m.markValue());
        e.setCurrency(m.currency());
        e.setCurveVersionSet(m.curveVersionSet().toString());
        e.setFxVersion(m.fxVersion());
        e.setVolumeVersionSet(m.volumeVersionSet() != null ? m.volumeVersionSet().toString() : null);
        e.setExpressionVersion(m.expressionVersion());
        e.setSupersedesId(m.supersedesId());
        e.setCreatedAt(m.createdAt());
        e.setRestrike(m.isRestrike());
        return e;
    }

    private StruckMark toDomain(StruckMarkEntity e) {
        return new StruckMark(
            e.getTenantId(), e.getPositionId(),
            YearMonth.parse(e.getDeliveryMonth()),
            e.getStrikeDate(), e.getMarkValue(), e.getCurrency(),
            Map.of(), e.getFxVersion(), Map.of(),
            e.getExpressionVersion(), e.getSupersedesId(),
            e.isRestrike(), e.getCreatedAt());
    }
}
