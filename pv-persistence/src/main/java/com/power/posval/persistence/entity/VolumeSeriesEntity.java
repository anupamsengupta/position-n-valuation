package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** §6.1 — append-only volume-series aggregate root. */
@Entity
@Table(name = "volume_series", schema = "volume_series",
    indexes = {
        @Index(name = "idx_vs_series_key_version",
               columnList = "tenant_id, series_key, version_id"),
        @Index(name = "idx_vs_asset", columnList = "asset_id"),
        @Index(name = "idx_vs_trade_leg", columnList = "trade_leg_id")
    })
public class VolumeSeriesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vs_seq")
    @SequenceGenerator(name = "vs_seq",
                       sequenceName = "volume_series.volume_series_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "series_uuid", nullable = false, unique = true)
    private UUID seriesUuid;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "series_key", length = 128, nullable = false)
    private String seriesKey;

    @Column(name = "series_type", nullable = false, length = 32)
    private String seriesType;

    @Column(name = "asset_id", length = 64)
    private String assetId;

    @Column(name = "trade_leg_id", length = 64)
    private String tradeLegId;

    @Column(name = "version_id", nullable = false)
    private long versionId;

    @Column(name = "quality_state", nullable = false, length = 16)
    private String qualityState;

    @Column(name = "materialization_status", nullable = false, length = 16)
    private String materializationStatus;

    @Column(name = "transaction_time", nullable = false)
    private Instant transactionTime;

    @Column(name = "valid_time")
    private Instant validTime;

    @OneToMany(mappedBy = "series", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("intervalStart ASC")
    private List<VolumeIntervalEntity> intervals = new ArrayList<>();

    // --- accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getSeriesUuid() { return seriesUuid; }
    public void setSeriesUuid(UUID seriesUuid) { this.seriesUuid = seriesUuid; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSeriesKey() { return seriesKey; }
    public void setSeriesKey(String seriesKey) { this.seriesKey = seriesKey; }

    public String getSeriesType() { return seriesType; }
    public void setSeriesType(String seriesType) { this.seriesType = seriesType; }

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }

    public String getTradeLegId() { return tradeLegId; }
    public void setTradeLegId(String tradeLegId) { this.tradeLegId = tradeLegId; }

    public long getVersionId() { return versionId; }
    public void setVersionId(long versionId) { this.versionId = versionId; }

    public String getQualityState() { return qualityState; }
    public void setQualityState(String qualityState) { this.qualityState = qualityState; }

    public String getMaterializationStatus() { return materializationStatus; }
    public void setMaterializationStatus(String materializationStatus) { this.materializationStatus = materializationStatus; }

    public Instant getTransactionTime() { return transactionTime; }
    public void setTransactionTime(Instant transactionTime) { this.transactionTime = transactionTime; }

    public Instant getValidTime() { return validTime; }
    public void setValidTime(Instant validTime) { this.validTime = validTime; }

    public List<VolumeIntervalEntity> getIntervals() { return intervals; }
    public void setIntervals(List<VolumeIntervalEntity> intervals) { this.intervals = intervals; }
}
