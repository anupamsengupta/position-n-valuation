package com.power.posval.persistence.entity;

import com.power.posval.domain.model.NominationRecord;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code nomination_record} table.
 * Per-interval nomination volume submitted to the TSO for a balancing group.
 * Versioned monotonically via {@code nomination_version}; not bitemporal.
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-VOL-03.
 */
@Entity
@Table(name = "nomination_record", schema = "da",
    indexes = {
        @Index(name = "idx_nr_tenant_bg_day_start",
               columnList = "tenant_id, balancing_group_id, delivery_day, interval_start")
    })
public class NominationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "nr_seq")
    @SequenceGenerator(name = "nr_seq",
                       sequenceName = "da.nomination_record_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "nomination_id", nullable = false, unique = true)
    private UUID nominationId;

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

    @Column(name = "nominated_volume_mw", nullable = false, precision = 18, scale = 8)
    private BigDecimal nominatedVolumeMw;

    @Column(name = "nomination_timestamp", nullable = false)
    private Instant nominationTimestamp;

    @Column(name = "nomination_version", nullable = false)
    private int nominationVersion;

    @Column(name = "submitted_by", length = 128)
    private String submittedBy;

    // --- JPA ---

    protected NominationRecordEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getNominationId() { return nominationId; }
    public void setNominationId(UUID nominationId) { this.nominationId = nominationId; }

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
    public void setNominatedVolumeMw(BigDecimal nominatedVolumeMw) { this.nominatedVolumeMw = nominatedVolumeMw; }

    public Instant getNominationTimestamp() { return nominationTimestamp; }
    public void setNominationTimestamp(Instant nominationTimestamp) { this.nominationTimestamp = nominationTimestamp; }

    public int getNominationVersion() { return nominationVersion; }
    public void setNominationVersion(int nominationVersion) { this.nominationVersion = nominationVersion; }

    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }

    // --- Domain conversion ---

    public NominationRecord toDomain() {
        return NominationRecord.builder()
            .nominationId(this.nominationId)
            .tenantId(this.tenantId)
            .balancingGroupId(this.balancingGroupId)
            .deliveryDay(this.deliveryDay)
            .intervalStart(this.intervalStart)
            .intervalEnd(this.intervalEnd)
            .nominatedVolumeMw(this.nominatedVolumeMw)
            .nominationTimestamp(this.nominationTimestamp)
            .nominationVersion(this.nominationVersion)
            .submittedBy(this.submittedBy)
            .build();
    }

    public static NominationRecordEntity fromDomain(NominationRecord d) {
        NominationRecordEntity e = new NominationRecordEntity();
        e.setNominationId(d.nominationId());
        e.setTenantId(d.tenantId());
        e.setBalancingGroupId(d.balancingGroupId());
        e.setDeliveryDay(d.deliveryDay());
        e.setIntervalStart(d.intervalStart());
        e.setIntervalEnd(d.intervalEnd());
        e.setNominatedVolumeMw(d.nominatedVolumeMw());
        e.setNominationTimestamp(d.nominationTimestamp());
        e.setNominationVersion(d.nominationVersion());
        e.setSubmittedBy(d.submittedBy());
        return e;
    }
}
