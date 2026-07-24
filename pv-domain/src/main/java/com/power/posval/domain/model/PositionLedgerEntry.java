package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Bitemporal position ledger entry. One row per trade-leg per delivery-month per version.
 * Immutable after construction — all fields set at creation time via Builder.
 * Pattern #1, #6, #34, #35, FR-030, D-1, S1.
 */
public final class PositionLedgerEntry {

    private final UUID id;
    private final String tenantId;
    private final String tradeId;
    private final String tradeLegId;
    private final int tradeVersion;
    private final DeliveryRange deliveryRange;
    private final BigDecimal quantity;          // signed: +long, -short
    private final VolumeUnit volumeUnit;
    private final UUID priceExpressionId;
    private final String portfolioId;
    private final String deliveryPointId;
    private final String originType;
    private final SeriesKey volumeSeriesKey;    // nullable for flat-block (FR-056a)
    private final String cascadeParentId;       // nullable (FR-03A)
    private final int cascadeGeneration;        // 0 for originals
    // Bitemporal axes
    private final Instant validFrom;
    private final Instant validTo;              // null = open-ended
    private final Instant knownFrom;
    private final Instant knownTo;              // null = current knowledge
    private final String status;                // ACTIVE, SUPERSEDED, CANCELLED
    private final String amendmentReason;       // BACKDATED_CORRECTION, FORWARD_EFFECTIVE

    private PositionLedgerEntry(Builder b) {
        this.id = b.id;
        this.tenantId = b.tenantId;
        this.tradeId = b.tradeId;
        this.tradeLegId = b.tradeLegId;
        this.tradeVersion = b.tradeVersion;
        this.deliveryRange = b.deliveryRange;
        this.quantity = b.quantity;
        this.volumeUnit = b.volumeUnit;
        this.priceExpressionId = b.priceExpressionId;
        this.portfolioId = b.portfolioId;
        this.deliveryPointId = b.deliveryPointId;
        this.originType = b.originType;
        this.volumeSeriesKey = b.volumeSeriesKey;
        this.cascadeParentId = b.cascadeParentId;
        this.cascadeGeneration = b.cascadeGeneration;
        this.validFrom = b.validFrom;
        this.validTo = b.validTo;
        this.knownFrom = b.knownFrom;
        this.knownTo = b.knownTo;
        this.status = b.status;
        this.amendmentReason = b.amendmentReason;
    }

    public static Builder builder() { return new Builder(); }

    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public String tradeId() { return tradeId; }
    public String tradeLegId() { return tradeLegId; }
    public int tradeVersion() { return tradeVersion; }
    public DeliveryRange deliveryRange() { return deliveryRange; }
    public BigDecimal quantity() { return quantity; }
    public VolumeUnit volumeUnit() { return volumeUnit; }
    public UUID priceExpressionId() { return priceExpressionId; }
    public String portfolioId() { return portfolioId; }
    public String deliveryPointId() { return deliveryPointId; }
    public String originType() { return originType; }
    public SeriesKey volumeSeriesKey() { return volumeSeriesKey; }
    public String cascadeParentId() { return cascadeParentId; }
    public int cascadeGeneration() { return cascadeGeneration; }
    public Instant validFrom() { return validFrom; }
    public Instant validTo() { return validTo; }
    public Instant knownFrom() { return knownFrom; }
    public Instant knownTo() { return knownTo; }
    public String status() { return status; }
    public String amendmentReason() { return amendmentReason; }

    public boolean isCurrentKnowledge() { return knownTo == null; }

    public static final class Builder {
        private UUID id;
        private String tenantId;
        private String tradeId;
        private String tradeLegId;
        private int tradeVersion;
        private DeliveryRange deliveryRange;
        private BigDecimal quantity;
        private VolumeUnit volumeUnit;
        private UUID priceExpressionId;
        private String portfolioId;
        private String deliveryPointId;
        private String originType;
        private SeriesKey volumeSeriesKey;
        private String cascadeParentId;
        private int cascadeGeneration;
        private Instant validFrom;
        private Instant validTo;
        private Instant knownFrom;
        private Instant knownTo;
        private String status;
        private String amendmentReason;

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder tradeId(String v) { this.tradeId = v; return this; }
        public Builder tradeLegId(String v) { this.tradeLegId = v; return this; }
        public Builder tradeVersion(int v) { this.tradeVersion = v; return this; }
        public Builder deliveryRange(DeliveryRange v) { this.deliveryRange = v; return this; }
        public Builder quantity(BigDecimal v) { this.quantity = v; return this; }
        public Builder volumeUnit(VolumeUnit v) { this.volumeUnit = v; return this; }
        public Builder priceExpressionId(UUID v) { this.priceExpressionId = v; return this; }
        public Builder portfolioId(String v) { this.portfolioId = v; return this; }
        public Builder deliveryPointId(String v) { this.deliveryPointId = v; return this; }
        public Builder originType(String v) { this.originType = v; return this; }
        public Builder volumeSeriesKey(SeriesKey v) { this.volumeSeriesKey = v; return this; }
        public Builder cascadeParentId(String v) { this.cascadeParentId = v; return this; }
        public Builder cascadeGeneration(int v) { this.cascadeGeneration = v; return this; }
        public Builder validFrom(Instant v) { this.validFrom = v; return this; }
        public Builder validTo(Instant v) { this.validTo = v; return this; }
        public Builder knownFrom(Instant v) { this.knownFrom = v; return this; }
        public Builder knownTo(Instant v) { this.knownTo = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder amendmentReason(String v) { this.amendmentReason = v; return this; }

        public PositionLedgerEntry build() {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(tradeLegId, "tradeLegId");
            Objects.requireNonNull(deliveryRange, "deliveryRange");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(volumeUnit, "volumeUnit");
            Objects.requireNonNull(priceExpressionId, "priceExpressionId");
            Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(knownFrom, "knownFrom");
            return new PositionLedgerEntry(this);
        }
    }
}
