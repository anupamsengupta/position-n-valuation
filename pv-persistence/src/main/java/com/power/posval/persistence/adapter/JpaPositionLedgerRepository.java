package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.persistence.entity.PositionLedgerEntryEntity;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for PositionLedgerRepository. §8.3, Pattern #18.
 * Bitemporal JPQL queries (TR-012).
 */
public class JpaPositionLedgerRepository implements PositionLedgerRepository {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaPositionLedgerRepository(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void save(PositionLedgerEntry entry) {
        emProvider.get().persist(toEntity(entry));
    }

    @Override
    public Optional<PositionLedgerEntry> findById(UUID entryId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.entryUuid = :uuid
                """, PositionLedgerEntryEntity.class)
            .setParameter("uuid", entryId)
            .getResultStream()
            .findFirst()
            .map(this::toDomain);
    }

    @Override
    public List<PositionLedgerEntry> findCurrentByTradeLeg(String tenantId,
                                                            String tradeId,
                                                            String tradeLegId) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.tradeId    = :tradeId
                  AND e.tradeLegId = :tradeLegId
                  AND e.knownTo IS NULL
                ORDER BY e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeId", tradeId)
            .setParameter("tradeLegId", tradeLegId)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<PositionLedgerEntry> findAsOf(String tenantId,
                                               String tradeId,
                                               Instant businessDate,
                                               Instant knowledgeDate) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.tradeId    = :tradeId
                  AND e.validFrom <= :businessDate
                  AND (e.validTo   IS NULL OR e.validTo > :businessDate)
                  AND e.knownFrom <= :knowledgeDate
                  AND (e.knownTo   IS NULL OR e.knownTo > :knowledgeDate)
                ORDER BY e.tradeLegId, e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeId", tradeId)
            .setParameter("businessDate", businessDate)
            .setParameter("knowledgeDate", knowledgeDate)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<PositionLedgerEntry> findByDeliveryRange(String tenantId,
                                                          Instant deliveryStart,
                                                          Instant deliveryEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId = :tenantId
                  AND e.deliveryStart < :deliveryEnd
                  AND e.deliveryEnd > :deliveryStart
                  AND e.knownTo IS NULL
                ORDER BY e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("deliveryStart", deliveryStart)
            .setParameter("deliveryEnd", deliveryEnd)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public void supersede(List<PositionLedgerEntry> entriesToClose,
                          List<PositionLedgerEntry> newEntries) {
        EntityManager em = emProvider.get();
        Instant now = Instant.now();

        for (PositionLedgerEntry entry : entriesToClose) {
            em.createQuery("""
                UPDATE PositionLedgerEntryEntity e
                SET e.knownTo = :now
                WHERE e.entryUuid = :uuid
                """)
                .setParameter("now", now)
                .setParameter("uuid", entry.id())
                .executeUpdate();
        }

        newEntries.forEach(entry -> em.persist(toEntity(entry)));
    }

    private PositionLedgerEntryEntity toEntity(PositionLedgerEntry d) {
        var e = new PositionLedgerEntryEntity();
        e.setEntryUuid(d.id() != null ? d.id() : UUID.randomUUID());
        e.setTenantId(d.tenantId());
        e.setTradeId(d.tradeId());
        e.setTradeLegId(d.tradeLegId());
        e.setTradeVersion(d.tradeVersion());
        e.setDeliveryStart(d.deliveryRange().startInstant().toInstant());
        e.setDeliveryEnd(d.deliveryRange().endInstant().toInstant());
        e.setDeliveryTimezone(d.deliveryRange().deliveryTimezone().getId());
        e.setQuantity(d.quantity());
        e.setVolumeUnit(d.volumeUnit().name());
        e.setPriceExpressionId(d.priceExpressionId());
        e.setVolumeSeriesKey(d.volumeSeriesKey() != null ? d.volumeSeriesKey().value() : null);
        e.setValidFrom(d.validFrom());
        e.setValidTo(d.validTo());
        e.setKnownFrom(d.knownFrom());
        e.setKnownTo(d.knownTo());
        e.setStatus(d.status() != null ? d.status() : "ACTIVE");
        e.setCascadeParentId(d.cascadeParentId());
        e.setCascadeGeneration(d.cascadeGeneration());
        return e;
    }

    private PositionLedgerEntry toDomain(PositionLedgerEntryEntity e) {
        ZoneId tz = ZoneId.of(e.getDeliveryTimezone());
        return PositionLedgerEntry.builder()
            .id(e.getEntryUuid())
            .tenantId(e.getTenantId())
            .tradeId(e.getTradeId())
            .tradeLegId(e.getTradeLegId())
            .tradeVersion(e.getTradeVersion())
            .deliveryRange(new DeliveryRange(
                YearMonth.from(e.getDeliveryStart().atZone(tz)),
                YearMonth.from(e.getDeliveryEnd().minusNanos(1).atZone(tz)),
                tz))
            .quantity(e.getQuantity())
            .volumeUnit(VolumeUnit.valueOf(e.getVolumeUnit()))
            .priceExpressionId(e.getPriceExpressionId())
            .volumeSeriesKey(e.getVolumeSeriesKey() != null
                ? new SeriesKey(e.getVolumeSeriesKey()) : null)
            .validFrom(e.getValidFrom())
            .validTo(e.getValidTo())
            .knownFrom(e.getKnownFrom())
            .knownTo(e.getKnownTo())
            .status(e.getStatus())
            .cascadeParentId(e.getCascadeParentId())
            .cascadeGeneration(e.getCascadeGeneration())
            .build();
    }
}
