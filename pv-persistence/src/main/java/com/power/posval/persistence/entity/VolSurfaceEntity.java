package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Market data volatility surface point. Schema: market_data.vol_surface. */
@Entity
@Table(name = "vol_surface", schema = "market_data",
    indexes = {
        @Index(name = "idx_vs_tenant_surface_strike_tenor_asof",
               columnList = "tenant_id, surface_id, strike_delta, expiry_tenor, as_of_date DESC")
    })
public class VolSurfaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vs_seq")
    @SequenceGenerator(name = "vs_seq",
                       sequenceName = "market_data.vol_surface_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "surface_id", nullable = false)
    private String surfaceId;

    @Column(name = "strike_delta", nullable = false)
    private double strikeDelta;

    @Column(name = "expiry_tenor", nullable = false)
    private String expiryTenor;

    @Column(name = "as_of_date", nullable = false)
    private Instant asOfDate;

    @Column(name = "implied_volatility", nullable = false, precision = 18, scale = 8)
    private BigDecimal impliedVolatility;

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
    public String getSurfaceId() { return surfaceId; }
    public void setSurfaceId(String surfaceId) { this.surfaceId = surfaceId; }
    public double getStrikeDelta() { return strikeDelta; }
    public void setStrikeDelta(double strikeDelta) { this.strikeDelta = strikeDelta; }
    public String getExpiryTenor() { return expiryTenor; }
    public void setExpiryTenor(String expiryTenor) { this.expiryTenor = expiryTenor; }
    public Instant getAsOfDate() { return asOfDate; }
    public void setAsOfDate(Instant asOfDate) { this.asOfDate = asOfDate; }
    public BigDecimal getImpliedVolatility() { return impliedVolatility; }
    public void setImpliedVolatility(BigDecimal impliedVolatility) { this.impliedVolatility = impliedVolatility; }
    public long getVersionId() { return versionId; }
    public void setVersionId(long versionId) { this.versionId = versionId; }
    public String getQualityState() { return qualityState; }
    public void setQualityState(String qualityState) { this.qualityState = qualityState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
