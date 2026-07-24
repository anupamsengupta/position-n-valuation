package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** §6.1 — individual volume interval within a series. */
@Entity
@Table(name = "volume_interval", schema = "volume_series")
public class VolumeIntervalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vi_seq")
    @SequenceGenerator(name = "vi_seq",
                       sequenceName = "volume_series.volume_interval_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "interval_uuid", nullable = false)
    private UUID intervalUuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private VolumeSeriesEntity series;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "volume", nullable = false, precision = 15, scale = 8)
    private BigDecimal volume;

    @Column(name = "energy", nullable = false, precision = 18, scale = 8)
    private BigDecimal energy;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "supersedes_id")
    private Long supersedesId;

    // --- accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getIntervalUuid() { return intervalUuid; }
    public void setIntervalUuid(UUID intervalUuid) { this.intervalUuid = intervalUuid; }

    public VolumeSeriesEntity getSeries() { return series; }
    public void setSeries(VolumeSeriesEntity series) { this.series = series; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getIntervalStart() { return intervalStart; }
    public void setIntervalStart(Instant intervalStart) { this.intervalStart = intervalStart; }

    public Instant getIntervalEnd() { return intervalEnd; }
    public void setIntervalEnd(Instant intervalEnd) { this.intervalEnd = intervalEnd; }

    public BigDecimal getVolume() { return volume; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }

    public BigDecimal getEnergy() { return energy; }
    public void setEnergy(BigDecimal energy) { this.energy = energy; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Long getSupersedesId() { return supersedesId; }
    public void setSupersedesId(Long supersedesId) { this.supersedesId = supersedesId; }
}
