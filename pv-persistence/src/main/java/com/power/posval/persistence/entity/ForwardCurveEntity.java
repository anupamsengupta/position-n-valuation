package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Market data forward curve point. Schema: market_data.forward_curve. */
@Entity
@Table(name = "forward_curve", schema = "market_data",
    indexes = {
        @Index(name = "idx_fc_tenant_series_pillar_asof",
               columnList = "tenant_id, series, pillar, as_of_date DESC")
    })
public class ForwardCurveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "fc_seq")
    @SequenceGenerator(name = "fc_seq",
                       sequenceName = "market_data.forward_curve_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "series", nullable = false)
    private String series;

    @Column(name = "pillar", nullable = false, length = 7)
    private String pillar;

    @Column(name = "as_of_date", nullable = false)
    private Instant asOfDate;

    @Column(name = "value", nullable = false, precision = 18, scale = 8)
    private BigDecimal value;

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
    public String getSeries() { return series; }
    public void setSeries(String series) { this.series = series; }
    public String getPillar() { return pillar; }
    public void setPillar(String pillar) { this.pillar = pillar; }
    public Instant getAsOfDate() { return asOfDate; }
    public void setAsOfDate(Instant asOfDate) { this.asOfDate = asOfDate; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public long getVersionId() { return versionId; }
    public void setVersionId(long versionId) { this.versionId = versionId; }
    public String getQualityState() { return qualityState; }
    public void setQualityState(String qualityState) { this.qualityState = qualityState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
