package com.power.posval.persistence.entity;

import com.power.posval.domain.model.ImbalanceRecord;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code imbalance_record} table.
 * Per-interval imbalance settlement measure derived from nominated vs TSO-metered volumes.
 * Not bitemporal — TSO corrections increment {@code record_version}.
 * {@code imbalance_amount} is stored at MONETARY precision (scale 4).
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-SET-04.
 */
@Entity
@Table(name = "imbalance_record", schema = "da",
    indexes = {
        @Index(name = "idx_ir_tenant_bg_day",
               columnList = "tenant_id, balancing_group_id, delivery_day"),
        @Index(name = "idx_ir_tenant_bg_start",
               columnList = "tenant_id, balancing_group_id, interval_start")
    })
public class ImbalanceRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ir_seq")
    @SequenceGenerator(name = "ir_seq",
                       sequenceName = "da.imbalance_record_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "record_id", nullable = false, unique = true)
    private UUID recordId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "balancing_group_id", nullable = false, length = 64)
    private String balancingGroupId;

    @Column(name = "delivery_day", nullable = false)
    private LocalDate deliveryDay;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "nominated_volume_mw", precision = 18, scale = 8)
    private BigDecimal nominatedVolumeMw;

    @Column(name = "actual_delivered_mw", precision = 18, scale = 8)
    private BigDecimal actualDeliveredMw;

    @Column(name = "imbalance_volume_mw", precision = 18, scale = 8)
    private BigDecimal imbalanceVolumeMw;

    @Column(name = "imbalance_energy_mwh", precision = 18, scale = 8)
    private BigDecimal imbalanceEnergyMwh;

    @Column(name = "imbalance_price_mwh", precision = 18, scale = 8)
    private BigDecimal imbalancePriceMwh;

    /** MONETARY scale 4 — DA-SET-04. */
    @Column(name = "imbalance_amount", precision = 18, scale = 4)
    private BigDecimal imbalanceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "tso_data_source", length = 128)
    private String tsoDataSource;

    @Column(name = "tso_publication_timestamp")
    private Instant tsoPublicationTimestamp;

    @Column(name = "record_version", nullable = false)
    private int recordVersion;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    // --- JPA ---

    protected ImbalanceRecordEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getRecordId() { return recordId; }
    public void setRecordId(UUID recordId) { this.recordId = recordId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBalancingGroupId() { return balancingGroupId; }
    public void setBalancingGroupId(String balancingGroupId) { this.balancingGroupId = balancingGroupId; }

    public LocalDate getDeliveryDay() { return deliveryDay; }
    public void setDeliveryDay(LocalDate deliveryDay) { this.deliveryDay = deliveryDay; }

    public Instant getIntervalStart() { return intervalStart; }
    public void setIntervalStart(Instant intervalStart) { this.intervalStart = intervalStart; }

    public Instant getIntervalEnd() { return intervalEnd; }
    public void setIntervalEnd(Instant intervalEnd) { this.intervalEnd = intervalEnd; }

    public BigDecimal getNominatedVolumeMw() { return nominatedVolumeMw; }
    public void setNominatedVolumeMw(BigDecimal v) { this.nominatedVolumeMw = v; }

    public BigDecimal getActualDeliveredMw() { return actualDeliveredMw; }
    public void setActualDeliveredMw(BigDecimal v) { this.actualDeliveredMw = v; }

    public BigDecimal getImbalanceVolumeMw() { return imbalanceVolumeMw; }
    public void setImbalanceVolumeMw(BigDecimal v) { this.imbalanceVolumeMw = v; }

    public BigDecimal getImbalanceEnergyMwh() { return imbalanceEnergyMwh; }
    public void setImbalanceEnergyMwh(BigDecimal v) { this.imbalanceEnergyMwh = v; }

    public BigDecimal getImbalancePriceMwh() { return imbalancePriceMwh; }
    public void setImbalancePriceMwh(BigDecimal v) { this.imbalancePriceMwh = v; }

    public BigDecimal getImbalanceAmount() { return imbalanceAmount; }
    public void setImbalanceAmount(BigDecimal v) { this.imbalanceAmount = v; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getTsoDataSource() { return tsoDataSource; }
    public void setTsoDataSource(String tsoDataSource) { this.tsoDataSource = tsoDataSource; }

    public Instant getTsoPublicationTimestamp() { return tsoPublicationTimestamp; }
    public void setTsoPublicationTimestamp(Instant v) { this.tsoPublicationTimestamp = v; }

    public int getRecordVersion() { return recordVersion; }
    public void setRecordVersion(int recordVersion) { this.recordVersion = recordVersion; }

    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }

    // --- Domain conversion ---

    public ImbalanceRecord toDomain() {
        return ImbalanceRecord.builder()
            .recordId(this.recordId)
            .tenantId(this.tenantId)
            .balancingGroupId(this.balancingGroupId)
            .deliveryDay(this.deliveryDay)
            .intervalStart(this.intervalStart)
            .intervalEnd(this.intervalEnd)
            .nominatedVolumeMw(this.nominatedVolumeMw)
            .actualDeliveredMw(this.actualDeliveredMw)
            .imbalanceVolumeMw(this.imbalanceVolumeMw)
            .imbalanceEnergyMwh(this.imbalanceEnergyMwh)
            .imbalancePricePerMwh(this.imbalancePriceMwh)
            .imbalanceAmount(this.imbalanceAmount)
            .currency(this.currency)
            .tsoDataSource(this.tsoDataSource)
            .tsoPublicationTimestamp(this.tsoPublicationTimestamp)
            .recordVersion(this.recordVersion)
            .computedAt(this.computedAt)
            .build();
    }

    public static ImbalanceRecordEntity fromDomain(ImbalanceRecord d) {
        ImbalanceRecordEntity e = new ImbalanceRecordEntity();
        e.setRecordId(d.recordId());
        e.setTenantId(d.tenantId());
        e.setBalancingGroupId(d.balancingGroupId());
        e.setDeliveryDay(d.deliveryDay());
        e.setIntervalStart(d.intervalStart());
        e.setIntervalEnd(d.intervalEnd());
        e.setNominatedVolumeMw(d.nominatedVolumeMw());
        e.setActualDeliveredMw(d.actualDeliveredMw());
        e.setImbalanceVolumeMw(d.imbalanceVolumeMw());
        e.setImbalanceEnergyMwh(d.imbalanceEnergyMwh());
        e.setImbalancePriceMwh(d.imbalancePricePerMwh());
        e.setImbalanceAmount(d.imbalanceAmount());
        e.setCurrency(d.currency());
        e.setTsoDataSource(d.tsoDataSource());
        e.setTsoPublicationTimestamp(d.tsoPublicationTimestamp());
        e.setRecordVersion(d.recordVersion());
        e.setComputedAt(d.computedAt());
        return e;
    }
}
