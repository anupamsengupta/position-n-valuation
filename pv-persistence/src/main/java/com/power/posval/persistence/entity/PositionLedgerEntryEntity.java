package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** §6.3, Pattern #35 — bitemporal position ledger row. */
@Entity
@Table(name = "position_ledger_entry", schema = "position",
    indexes = {
        @Index(name = "idx_ple_tenant_trade",
               columnList = "tenant_id, trade_id, trade_leg_id"),
        @Index(name = "idx_ple_tenant_delivery",
               columnList = "tenant_id, delivery_start, delivery_end"),
        @Index(name = "idx_ple_current_knowledge",
               columnList = "tenant_id, trade_id, known_to"),
        @Index(name = "idx_ple_bitemporal",
               columnList = "tenant_id, valid_from, valid_to, known_from, known_to")
    })
@EntityListeners(BitemporalAuditListener.class)
public class PositionLedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ple_seq")
    @SequenceGenerator(name = "ple_seq",
                       sequenceName = "position.position_ledger_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "entry_uuid", nullable = false, unique = true)
    private UUID entryUuid;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "trade_id", length = 64, nullable = false)
    private String tradeId;

    @Column(name = "trade_leg_id", length = 64, nullable = false)
    private String tradeLegId;

    @Column(name = "trade_version", nullable = false)
    private int tradeVersion;

    @Column(name = "delivery_start", nullable = false)
    private Instant deliveryStart;

    @Column(name = "delivery_end", nullable = false)
    private Instant deliveryEnd;

    @Column(name = "delivery_timezone", length = 64, nullable = false)
    private String deliveryTimezone;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 8)
    private BigDecimal quantity;

    @Column(name = "volume_unit", nullable = false, length = 16)
    private String volumeUnit;

    @Column(name = "price_expression_id", nullable = false)
    private UUID priceExpressionId;

    @Column(name = "volume_series_key", length = 128)
    private String volumeSeriesKey;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "known_from", nullable = false)
    private Instant knownFrom;

    @Column(name = "known_to")
    private Instant knownTo;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "cascade_parent_id", length = 64)
    private String cascadeParentId;

    @Column(name = "cascade_generation", nullable = false)
    private int cascadeGeneration = 0;

    // --- accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getEntryUuid() { return entryUuid; }
    public void setEntryUuid(UUID entryUuid) { this.entryUuid = entryUuid; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTradeId() { return tradeId; }
    public void setTradeId(String tradeId) { this.tradeId = tradeId; }

    public String getTradeLegId() { return tradeLegId; }
    public void setTradeLegId(String tradeLegId) { this.tradeLegId = tradeLegId; }

    public int getTradeVersion() { return tradeVersion; }
    public void setTradeVersion(int tradeVersion) { this.tradeVersion = tradeVersion; }

    public Instant getDeliveryStart() { return deliveryStart; }
    public void setDeliveryStart(Instant deliveryStart) { this.deliveryStart = deliveryStart; }

    public Instant getDeliveryEnd() { return deliveryEnd; }
    public void setDeliveryEnd(Instant deliveryEnd) { this.deliveryEnd = deliveryEnd; }

    public String getDeliveryTimezone() { return deliveryTimezone; }
    public void setDeliveryTimezone(String deliveryTimezone) { this.deliveryTimezone = deliveryTimezone; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getVolumeUnit() { return volumeUnit; }
    public void setVolumeUnit(String volumeUnit) { this.volumeUnit = volumeUnit; }

    public UUID getPriceExpressionId() { return priceExpressionId; }
    public void setPriceExpressionId(UUID priceExpressionId) { this.priceExpressionId = priceExpressionId; }

    public String getVolumeSeriesKey() { return volumeSeriesKey; }
    public void setVolumeSeriesKey(String volumeSeriesKey) { this.volumeSeriesKey = volumeSeriesKey; }

    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }

    public Instant getValidTo() { return validTo; }
    public void setValidTo(Instant validTo) { this.validTo = validTo; }

    public Instant getKnownFrom() { return knownFrom; }
    public void setKnownFrom(Instant knownFrom) { this.knownFrom = knownFrom; }

    public Instant getKnownTo() { return knownTo; }
    public void setKnownTo(Instant knownTo) { this.knownTo = knownTo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCascadeParentId() { return cascadeParentId; }
    public void setCascadeParentId(String cascadeParentId) { this.cascadeParentId = cascadeParentId; }

    public int getCascadeGeneration() { return cascadeGeneration; }
    public void setCascadeGeneration(int cascadeGeneration) { this.cascadeGeneration = cascadeGeneration; }
}
