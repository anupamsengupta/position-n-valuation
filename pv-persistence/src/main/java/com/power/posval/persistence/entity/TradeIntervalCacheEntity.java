package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** §12 — S6b trade-interval cache row. */
@Entity
@Table(name = "trade_interval_cache", schema = "volume_series",
    indexes = {
        @Index(name = "idx_tic_trade_leg_time",
               columnList = "trade_leg_id, interval_start"),
        @Index(name = "idx_tic_tenant_time",
               columnList = "tenant_id, interval_start")
    })
public class TradeIntervalCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tic_seq")
    @SequenceGenerator(name = "tic_seq",
                       sequenceName = "volume_series.trade_interval_cache_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "trade_leg_id", length = 64, nullable = false)
    private String tradeLegId;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "resolved_qty", nullable = false, precision = 15, scale = 8)
    private BigDecimal resolvedQty;

    @Column(name = "resolved_energy", nullable = false, precision = 18, scale = 8)
    private BigDecimal resolvedEnergy;

    @Column(name = "multiplier", nullable = false, precision = 8, scale = 8)
    private BigDecimal multiplier;

    @Column(name = "series_key", length = 128, nullable = false)
    private String seriesKey;

    @Column(name = "version_hash", length = 64, nullable = false)
    private String versionHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // --- accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTradeLegId() { return tradeLegId; }
    public void setTradeLegId(String tradeLegId) { this.tradeLegId = tradeLegId; }

    public Instant getIntervalStart() { return intervalStart; }
    public void setIntervalStart(Instant intervalStart) { this.intervalStart = intervalStart; }

    public Instant getIntervalEnd() { return intervalEnd; }
    public void setIntervalEnd(Instant intervalEnd) { this.intervalEnd = intervalEnd; }

    public BigDecimal getResolvedQty() { return resolvedQty; }
    public void setResolvedQty(BigDecimal resolvedQty) { this.resolvedQty = resolvedQty; }

    public BigDecimal getResolvedEnergy() { return resolvedEnergy; }
    public void setResolvedEnergy(BigDecimal resolvedEnergy) { this.resolvedEnergy = resolvedEnergy; }

    public BigDecimal getMultiplier() { return multiplier; }
    public void setMultiplier(BigDecimal multiplier) { this.multiplier = multiplier; }

    public String getSeriesKey() { return seriesKey; }
    public void setSeriesKey(String seriesKey) { this.seriesKey = seriesKey; }

    public String getVersionHash() { return versionHash; }
    public void setVersionHash(String versionHash) { this.versionHash = versionHash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
