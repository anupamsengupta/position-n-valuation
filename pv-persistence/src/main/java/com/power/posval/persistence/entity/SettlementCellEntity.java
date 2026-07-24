package com.power.posval.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** §11.1 — bitemporal settlement cell. */
@Entity
@Table(name = "settlement_cell", schema = "valuation",
    indexes = {
        @Index(name = "idx_sc_position_interval",
               columnList = "tenant_id, position_id, interval_start"),
        @Index(name = "idx_sc_current_knowledge",
               columnList = "tenant_id, position_id, known_to"),
        @Index(name = "idx_sc_bitemporal",
               columnList = "tenant_id, valid_from, valid_to, known_from, known_to")
    })
@EntityListeners(BitemporalAuditListener.class)
public class SettlementCellEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sc_seq")
    @SequenceGenerator(name = "sc_seq",
                       sequenceName = "valuation.settlement_cell_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "cell_uuid", nullable = false, unique = true)
    private UUID cellUuid;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "valuation_type", length = 16, nullable = false)
    private String valuationType;

    @Column(name = "cell_status", length = 16, nullable = false)
    private String cellStatus;

    @Column(name = "price", nullable = false, precision = 15, scale = 8)
    private BigDecimal price;

    @Column(name = "volume_mw", nullable = false, precision = 15, scale = 8)
    private BigDecimal volumeMw;

    @Column(name = "volume_mwh", nullable = false, precision = 18, scale = 8)
    private BigDecimal volumeMwh;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "active_leaves", columnDefinition = "jsonb")
    private String activeLeaves;

    @Column(name = "input_version_set", columnDefinition = "jsonb", nullable = false)
    private String inputVersionSet;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "known_from", nullable = false)
    private Instant knownFrom;

    @Column(name = "known_to")
    private Instant knownTo;

    // --- accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getCellUuid() { return cellUuid; }
    public void setCellUuid(UUID cellUuid) { this.cellUuid = cellUuid; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getPositionId() { return positionId; }
    public void setPositionId(UUID positionId) { this.positionId = positionId; }

    public Instant getIntervalStart() { return intervalStart; }
    public void setIntervalStart(Instant intervalStart) { this.intervalStart = intervalStart; }

    public Instant getIntervalEnd() { return intervalEnd; }
    public void setIntervalEnd(Instant intervalEnd) { this.intervalEnd = intervalEnd; }

    public String getValuationType() { return valuationType; }
    public void setValuationType(String valuationType) { this.valuationType = valuationType; }

    public String getCellStatus() { return cellStatus; }
    public void setCellStatus(String cellStatus) { this.cellStatus = cellStatus; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getVolumeMw() { return volumeMw; }
    public void setVolumeMw(BigDecimal volumeMw) { this.volumeMw = volumeMw; }

    public BigDecimal getVolumeMwh() { return volumeMwh; }
    public void setVolumeMwh(BigDecimal volumeMwh) { this.volumeMwh = volumeMwh; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getActiveLeaves() { return activeLeaves; }
    public void setActiveLeaves(String activeLeaves) { this.activeLeaves = activeLeaves; }

    public String getInputVersionSet() { return inputVersionSet; }
    public void setInputVersionSet(String inputVersionSet) { this.inputVersionSet = inputVersionSet; }

    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }

    public Instant getValidTo() { return validTo; }
    public void setValidTo(Instant validTo) { this.validTo = validTo; }

    public Instant getKnownFrom() { return knownFrom; }
    public void setKnownFrom(Instant knownFrom) { this.knownFrom = knownFrom; }

    public Instant getKnownTo() { return knownTo; }
    public void setKnownTo(Instant knownTo) { this.knownTo = knownTo; }
}
