package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Market data index value. Schema: market_data.index_value. */
@Entity
@Table(name = "index_value", schema = "market_data",
    indexes = {
        @Index(name = "idx_iv_tenant_series_refmonth",
               columnList = "tenant_id, series, ref_month_expression")
    })
public class IndexValueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "iv_seq")
    @SequenceGenerator(name = "iv_seq",
                       sequenceName = "market_data.index_value_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "series", nullable = false)
    private String series;

    @Column(name = "ref_month_expression", nullable = false)
    private String refMonthExpression;

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
    public String getRefMonthExpression() { return refMonthExpression; }
    public void setRefMonthExpression(String refMonthExpression) { this.refMonthExpression = refMonthExpression; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public long getVersionId() { return versionId; }
    public void setVersionId(long versionId) { this.versionId = versionId; }
    public String getQualityState() { return qualityState; }
    public void setQualityState(String qualityState) { this.qualityState = qualityState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
