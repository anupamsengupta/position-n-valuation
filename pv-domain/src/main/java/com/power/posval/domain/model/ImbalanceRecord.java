package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-interval imbalance settlement measure derived from nominated vs TSO-metered volumes.
 * Not bitemporal — versioned for TSO corrections via {@code recordVersion}.
 * {@code imbalanceVolumeMw = actualDeliveredMw - nominatedVolumeMw}
 * {@code imbalanceAmount} stored at MONETARY scale (4 decimal places, per NumericPrecision).
 * Pattern #1, S4.3, DA-SET-04.
 */
public final class ImbalanceRecord {

    private final UUID recordId;
    private final String tenantId;
    private final String balancingGroupId;
    private final Instant intervalStart;
    private final Instant intervalEnd;
    private final BigDecimal nominatedVolumeMw;
    private final BigDecimal actualDeliveredMw;
    private final BigDecimal imbalanceVolumeMw;
    private final BigDecimal imbalanceEnergyMwh;
    private final BigDecimal imbalancePricePerMwh;
    private final BigDecimal imbalanceAmount;
    private final String currency;
    private final String tsoDataSource;
    private final Instant tsoPublicationTimestamp;
    private final int recordVersion;
    private final Instant computedAt;
    private final LocalDate deliveryDay;

    private ImbalanceRecord(Builder b) {
        this.recordId                  = b.recordId;
        this.tenantId                  = b.tenantId;
        this.balancingGroupId          = b.balancingGroupId;
        this.intervalStart             = b.intervalStart;
        this.intervalEnd               = b.intervalEnd;
        this.nominatedVolumeMw         = b.nominatedVolumeMw;
        this.actualDeliveredMw         = b.actualDeliveredMw;
        this.imbalanceVolumeMw         = b.imbalanceVolumeMw;
        this.imbalanceEnergyMwh        = b.imbalanceEnergyMwh;
        this.imbalancePricePerMwh      = b.imbalancePricePerMwh;
        this.imbalanceAmount           = b.imbalanceAmount;
        this.currency                  = b.currency;
        this.tsoDataSource             = b.tsoDataSource;
        this.tsoPublicationTimestamp   = b.tsoPublicationTimestamp;
        this.recordVersion             = b.recordVersion;
        this.computedAt                = b.computedAt;
        this.deliveryDay               = b.deliveryDay;
    }

    public static Builder builder() { return new Builder(); }

    public UUID recordId()                  { return recordId; }
    public String tenantId()                { return tenantId; }
    public String balancingGroupId()        { return balancingGroupId; }
    public Instant intervalStart()          { return intervalStart; }
    public Instant intervalEnd()            { return intervalEnd; }
    public BigDecimal nominatedVolumeMw()   { return nominatedVolumeMw; }
    public BigDecimal actualDeliveredMw()   { return actualDeliveredMw; }
    public BigDecimal imbalanceVolumeMw()   { return imbalanceVolumeMw; }
    public BigDecimal imbalanceEnergyMwh()  { return imbalanceEnergyMwh; }
    public BigDecimal imbalancePricePerMwh() { return imbalancePricePerMwh; }
    public BigDecimal imbalanceAmount()     { return imbalanceAmount; }
    public String currency()               { return currency; }
    public String tsoDataSource()          { return tsoDataSource; }
    public Instant tsoPublicationTimestamp() { return tsoPublicationTimestamp; }
    public int recordVersion()             { return recordVersion; }
    public Instant computedAt()            { return computedAt; }
    public LocalDate deliveryDay()         { return deliveryDay; }

    public static final class Builder {
        private UUID recordId;
        private String tenantId;
        private String balancingGroupId;
        private Instant intervalStart;
        private Instant intervalEnd;
        private BigDecimal nominatedVolumeMw;
        private BigDecimal actualDeliveredMw;
        private BigDecimal imbalanceVolumeMw;
        private BigDecimal imbalanceEnergyMwh;
        private BigDecimal imbalancePricePerMwh;
        private BigDecimal imbalanceAmount;
        private String currency;
        private String tsoDataSource;
        private Instant tsoPublicationTimestamp;
        private int recordVersion;
        private Instant computedAt;
        private LocalDate deliveryDay;

        public Builder recordId(UUID v)                      { this.recordId = v; return this; }
        public Builder tenantId(String v)                    { this.tenantId = v; return this; }
        public Builder balancingGroupId(String v)            { this.balancingGroupId = v; return this; }
        public Builder intervalStart(Instant v)              { this.intervalStart = v; return this; }
        public Builder intervalEnd(Instant v)                { this.intervalEnd = v; return this; }
        public Builder nominatedVolumeMw(BigDecimal v)       { this.nominatedVolumeMw = v; return this; }
        public Builder actualDeliveredMw(BigDecimal v)       { this.actualDeliveredMw = v; return this; }
        public Builder imbalanceVolumeMw(BigDecimal v)       { this.imbalanceVolumeMw = v; return this; }
        public Builder imbalanceEnergyMwh(BigDecimal v)      { this.imbalanceEnergyMwh = v; return this; }
        public Builder imbalancePricePerMwh(BigDecimal v)    { this.imbalancePricePerMwh = v; return this; }
        public Builder imbalanceAmount(BigDecimal v)         { this.imbalanceAmount = v; return this; }
        public Builder currency(String v)                    { this.currency = v; return this; }
        public Builder tsoDataSource(String v)               { this.tsoDataSource = v; return this; }
        public Builder tsoPublicationTimestamp(Instant v)    { this.tsoPublicationTimestamp = v; return this; }
        public Builder recordVersion(int v)                  { this.recordVersion = v; return this; }
        public Builder computedAt(Instant v)                 { this.computedAt = v; return this; }
        public Builder deliveryDay(LocalDate v)              { this.deliveryDay = v; return this; }

        public ImbalanceRecord build() {
            Objects.requireNonNull(recordId,               "recordId");
            Objects.requireNonNull(tenantId,               "tenantId");
            Objects.requireNonNull(balancingGroupId,       "balancingGroupId");
            Objects.requireNonNull(intervalStart,          "intervalStart");
            Objects.requireNonNull(intervalEnd,            "intervalEnd");
            Objects.requireNonNull(nominatedVolumeMw,      "nominatedVolumeMw");
            Objects.requireNonNull(actualDeliveredMw,      "actualDeliveredMw");
            Objects.requireNonNull(imbalanceVolumeMw,      "imbalanceVolumeMw");
            Objects.requireNonNull(imbalanceEnergyMwh,     "imbalanceEnergyMwh");
            Objects.requireNonNull(imbalancePricePerMwh,   "imbalancePricePerMwh");
            Objects.requireNonNull(imbalanceAmount,        "imbalanceAmount");
            Objects.requireNonNull(currency,               "currency");
            Objects.requireNonNull(tsoDataSource,          "tsoDataSource");
            Objects.requireNonNull(tsoPublicationTimestamp, "tsoPublicationTimestamp");
            Objects.requireNonNull(computedAt,             "computedAt");
            Objects.requireNonNull(deliveryDay,            "deliveryDay");
            if (!intervalEnd.isAfter(intervalStart)) {
                throw new IllegalArgumentException("intervalEnd must be after intervalStart");
            }
            if (recordVersion < 1) {
                throw new IllegalArgumentException("recordVersion must be >= 1");
            }
            return new ImbalanceRecord(this);
        }
    }
}
