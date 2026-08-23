package com.power.posval.persistence.entity;

import com.power.posval.domain.model.ExchangeFeeSchedule;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code exchange_fee_schedule} table.
 * Tenant-scoped, effective-dated fee schedule per exchange, fee type, and member tier.
 * {@code rate_per_mwh} at PRICE precision (scale 8). {@code effective_to} nullable = open-ended.
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-SET-03.
 */
@Entity
@Table(name = "exchange_fee_schedule", schema = "da",
    indexes = {
        @Index(name = "idx_efs_tenant_exchange_type_from",
               columnList = "tenant_id, exchange, fee_type, effective_from")
    })
public class ExchangeFeeScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "efs_seq")
    @SequenceGenerator(name = "efs_seq",
                       sequenceName = "da.exchange_fee_schedule_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "schedule_id", nullable = false, unique = true)
    private UUID scheduleId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "exchange", nullable = false, length = 32)
    private String exchange;

    @Column(name = "fee_type", nullable = false, length = 16)
    private String feeType;

    /** PRICE scale 8 — DA-SET-03. */
    @Column(name = "rate_per_mwh", nullable = false, precision = 18, scale = 8)
    private BigDecimal ratePerMwh;

    /** Nullable — null means the rate applies to the default member tier. */
    @Column(name = "member_tier", length = 32)
    private String memberTier;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Nullable — null indicates open-ended (currently active). */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // --- JPA ---

    protected ExchangeFeeScheduleEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getScheduleId() { return scheduleId; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getFeeType() { return feeType; }
    public void setFeeType(String feeType) { this.feeType = feeType; }

    public BigDecimal getRatePerMwh() { return ratePerMwh; }
    public void setRatePerMwh(BigDecimal ratePerMwh) { this.ratePerMwh = ratePerMwh; }

    public String getMemberTier() { return memberTier; }
    public void setMemberTier(String memberTier) { this.memberTier = memberTier; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    // --- Domain conversion ---

    public ExchangeFeeSchedule toDomain() {
        return ExchangeFeeSchedule.builder()
            .scheduleId(this.scheduleId)
            .tenantId(this.tenantId)
            .exchange(this.exchange)
            .feeType(this.feeType)
            .ratePerMwh(this.ratePerMwh)
            .memberTier(this.memberTier)
            .effectiveFrom(this.effectiveFrom)
            .effectiveTo(this.effectiveTo)
            .build();
    }

    public static ExchangeFeeScheduleEntity fromDomain(ExchangeFeeSchedule d) {
        ExchangeFeeScheduleEntity e = new ExchangeFeeScheduleEntity();
        e.setScheduleId(d.scheduleId());
        e.setTenantId(d.tenantId());
        e.setExchange(d.exchange());
        e.setFeeType(d.feeType());
        e.setRatePerMwh(d.ratePerMwh());
        e.setMemberTier(d.memberTier());
        e.setEffectiveFrom(d.effectiveFrom());
        e.setEffectiveTo(d.effectiveTo());
        return e;
    }
}
