package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** §11.3 — immutable struck mark for EoD close. */
@Entity
@Table(name = "struck_mark", schema = "valuation",
    indexes = {
        @Index(name = "idx_sm_position_month",
               columnList = "tenant_id, position_id, delivery_month, strike_date"),
        @Index(name = "idx_sm_strike_date",
               columnList = "tenant_id, strike_date")
    })
public class StruckMarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sm_seq")
    @SequenceGenerator(name = "sm_seq",
                       sequenceName = "valuation.struck_mark_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "delivery_month", nullable = false, length = 7)
    private String deliveryMonth;

    @Column(name = "strike_date", nullable = false)
    private LocalDate strikeDate;

    @Column(name = "mark_value", nullable = false, precision = 18, scale = 4)
    private BigDecimal markValue;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "curve_version_set", columnDefinition = "jsonb", nullable = false)
    private String curveVersionSet;

    @Column(name = "fx_version", length = 64)
    private String fxVersion;

    @Column(name = "volume_version_set", columnDefinition = "jsonb")
    private String volumeVersionSet;

    @Column(name = "expression_version", nullable = false)
    private long expressionVersion;

    @Column(name = "supersedes_id")
    private Long supersedesId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_restrike", nullable = false)
    private boolean isRestrike;

    // --- accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getPositionId() { return positionId; }
    public void setPositionId(UUID positionId) { this.positionId = positionId; }

    public String getDeliveryMonth() { return deliveryMonth; }
    public void setDeliveryMonth(String deliveryMonth) { this.deliveryMonth = deliveryMonth; }

    public LocalDate getStrikeDate() { return strikeDate; }
    public void setStrikeDate(LocalDate strikeDate) { this.strikeDate = strikeDate; }

    public BigDecimal getMarkValue() { return markValue; }
    public void setMarkValue(BigDecimal markValue) { this.markValue = markValue; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCurveVersionSet() { return curveVersionSet; }
    public void setCurveVersionSet(String curveVersionSet) { this.curveVersionSet = curveVersionSet; }

    public String getFxVersion() { return fxVersion; }
    public void setFxVersion(String fxVersion) { this.fxVersion = fxVersion; }

    public String getVolumeVersionSet() { return volumeVersionSet; }
    public void setVolumeVersionSet(String volumeVersionSet) { this.volumeVersionSet = volumeVersionSet; }

    public long getExpressionVersion() { return expressionVersion; }
    public void setExpressionVersion(long expressionVersion) { this.expressionVersion = expressionVersion; }

    public Long getSupersedesId() { return supersedesId; }
    public void setSupersedesId(Long supersedesId) { this.supersedesId = supersedesId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isRestrike() { return isRestrike; }
    public void setRestrike(boolean restrike) { isRestrike = restrike; }
}
