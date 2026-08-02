package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Market data FX rate. Schema: market_data.fx_rate. */
@Entity
@Table(name = "fx_rate", schema = "market_data",
    indexes = {
        @Index(name = "idx_fx_tenant_pair_refdate",
               columnList = "tenant_id, currency_pair, reference_date DESC")
    })
public class FxRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "fx_seq")
    @SequenceGenerator(name = "fx_seq",
                       sequenceName = "market_data.fx_rate_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "currency_pair", nullable = false, length = 7)
    private String currencyPair;

    @Column(name = "reference_date", nullable = false)
    private Instant referenceDate;

    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(name = "version_id", nullable = false)
    private long versionId;

    @Column(name = "quality_state", nullable = false, length = 20)
    private String qualityState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCurrencyPair() { return currencyPair; }
    public void setCurrencyPair(String currencyPair) { this.currencyPair = currencyPair; }
    public Instant getReferenceDate() { return referenceDate; }
    public void setReferenceDate(Instant referenceDate) { this.referenceDate = referenceDate; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public long getVersionId() { return versionId; }
    public void setVersionId(long versionId) { this.versionId = versionId; }
    public String getQualityState() { return qualityState; }
    public void setQualityState(String qualityState) { this.qualityState = qualityState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
