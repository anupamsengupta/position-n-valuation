package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.persistence.batch.BatchWriter;
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
    private final BatchWriter batchWriter;

    @Inject
    public JpaPositionLedgerRepository(Provider<EntityManager> emProvider,
                                        BatchWriter batchWriter) {
        this.emProvider = emProvider;
        this.batchWriter = batchWriter;
    }

    @Override
    public void save(PositionLedgerEntry entry) {
        emProvider.get().persist(toEntity(entry));
    }

    @Override
    public void saveAll(List<PositionLedgerEntry> entries) {
        batchWriter.writeAll(entries.stream().map(this::toEntity).toList());
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
    public List<PositionLedgerEntry> findCurrentByTradeLegAndVersion(String tenantId,
                                                                       String tradeId,
                                                                       String tradeLegId,
                                                                       int tradeVersion) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId     = :tenantId
                  AND e.tradeId      = :tradeId
                  AND e.tradeLegId   = :tradeLegId
                  AND e.tradeVersion = :tradeVersion
                  AND e.knownTo IS NULL
                ORDER BY e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeId", tradeId)
            .setParameter("tradeLegId", tradeLegId)
            .setParameter("tradeVersion", tradeVersion)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<PositionLedgerEntry> findAsOf(String tenantId,
                                               String tradeId,
                                               String tradeLegId,
                                               Instant businessDate,
                                               Instant knowledgeDate) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.tradeId    = :tradeId
                  AND e.tradeLegId = :tradeLegId
                  AND e.validFrom <= :businessDate
                  AND (e.validTo   IS NULL OR e.validTo > :businessDate)
                  AND e.knownFrom <= :knowledgeDate
                  AND (e.knownTo   IS NULL OR e.knownTo > :knowledgeDate)
                ORDER BY e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeId", tradeId)
            .setParameter("tradeLegId", tradeLegId)
            .setParameter("businessDate", businessDate)
            .setParameter("knowledgeDate", knowledgeDate)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<PositionLedgerEntry> findAllByDeliveryRange(String tenantId,
                                                             Instant deliveryStart,
                                                             Instant deliveryEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId = :tenantId
                  AND e.deliveryStart < :deliveryEnd
                  AND e.deliveryEnd > :deliveryStart
                  AND e.knownTo IS NULL
                ORDER BY e.tradeLegId, e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("deliveryStart", deliveryStart)
            .setParameter("deliveryEnd", deliveryEnd)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String tenantId,
                                                                      String tradeId,
                                                                      String tradeLegId,
                                                                      Instant deliveryStart,
                                                                      Instant deliveryEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.tenantId   = :tenantId
                  AND e.tradeId    = :tradeId
                  AND e.tradeLegId = :tradeLegId
                  AND e.deliveryStart < :deliveryEnd
                  AND e.deliveryEnd > :deliveryStart
                  AND e.knownTo IS NULL
                ORDER BY e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", tenantId)
            .setParameter("tradeId", tradeId)
            .setParameter("tradeLegId", tradeLegId)
            .setParameter("deliveryStart", deliveryStart)
            .setParameter("deliveryEnd", deliveryEnd)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<PositionLedgerEntry> findCurrentByVolumeSeriesKeyAndDeliveryRange(
            String volumeSeriesKey,
            Instant deliveryStart,
            Instant deliveryEnd) {
        return emProvider.get()
            .createQuery("""
                SELECT e FROM PositionLedgerEntryEntity e
                WHERE e.volumeSeriesKey = :seriesKey
                  AND e.deliveryStart < :deliveryEnd
                  AND e.deliveryEnd > :deliveryStart
                  AND e.knownTo IS NULL
                ORDER BY e.tradeLegId, e.deliveryStart
                """, PositionLedgerEntryEntity.class)
            .setParameter("seriesKey", volumeSeriesKey)
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
        e.setDeliveryStart(d.deliveryStart());
        e.setDeliveryEnd(d.deliveryEnd());
        e.setDeliveryTimezone(d.deliveryRange().deliveryTimezone().getId());
        e.setQuantity(d.quantity());
        e.setVolumeUnit(d.volumeUnit().name());
        e.setPriceExpressionId(d.priceExpressionId());
        e.setMarketPriceExpressionId(d.marketPriceExpressionId());
        e.setVolumeSeriesKey(d.volumeSeriesKey() != null ? d.volumeSeriesKey().value() : null);
        e.setMultiplier(d.multiplier());
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
            .deliveryStart(e.getDeliveryStart())
            .deliveryEnd(e.getDeliveryEnd())
            .quantity(e.getQuantity())
            .volumeUnit(VolumeUnit.valueOf(e.getVolumeUnit()))
            .priceExpressionId(e.getPriceExpressionId())
            .marketPriceExpressionId(e.getMarketPriceExpressionId())
            .volumeSeriesKey(e.getVolumeSeriesKey() != null
                ? new SeriesKey(e.getVolumeSeriesKey()) : null)
            .multiplier(e.getMultiplier())
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
