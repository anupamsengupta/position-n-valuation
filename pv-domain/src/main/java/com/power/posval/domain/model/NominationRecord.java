package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-interval nomination volume submitted to the TSO for a balancing group.
 * Not bitemporal — versioned monotonically per (balancingGroupId, intervalStart)
 * via {@code nominationVersion}. A higher version always supersedes a lower one.
 * Pattern #2, S4.3, DA-VOL-03.
 */
public final class NominationRecord {

    private final UUID nominationId;
    private final String tenantId;
    private final String balancingGroupId;
    private final LocalDate deliveryDay;
    private final Instant intervalStart;
    private final Instant intervalEnd;
    private final BigDecimal nominatedVolumeMw;
    private final Instant nominationTimestamp;
    private final int nominationVersion;
    private final String submittedBy;

    private NominationRecord(Builder b) {
        this.nominationId       = b.nominationId;
        this.tenantId           = b.tenantId;
        this.balancingGroupId   = b.balancingGroupId;
        this.deliveryDay        = b.deliveryDay;
        this.intervalStart      = b.intervalStart;
        this.intervalEnd        = b.intervalEnd;
        this.nominatedVolumeMw  = b.nominatedVolumeMw;
        this.nominationTimestamp = b.nominationTimestamp;
        this.nominationVersion  = b.nominationVersion;
        this.submittedBy        = b.submittedBy;
    }

    public static Builder builder() { return new Builder(); }

    public UUID nominationId()          { return nominationId; }
    public String tenantId()            { return tenantId; }
    public String balancingGroupId()    { return balancingGroupId; }
    public LocalDate deliveryDay()      { return deliveryDay; }
    public Instant intervalStart()      { return intervalStart; }
    public Instant intervalEnd()        { return intervalEnd; }
    public BigDecimal nominatedVolumeMw() { return nominatedVolumeMw; }
    public Instant nominationTimestamp() { return nominationTimestamp; }
    public int nominationVersion()      { return nominationVersion; }
    public String submittedBy()         { return submittedBy; }

    public static final class Builder {
        private UUID nominationId;
        private String tenantId;
        private String balancingGroupId;
        private LocalDate deliveryDay;
        private Instant intervalStart;
        private Instant intervalEnd;
        private BigDecimal nominatedVolumeMw;
        private Instant nominationTimestamp;
        private int nominationVersion;
        private String submittedBy;

        public Builder nominationId(UUID v)             { this.nominationId = v; return this; }
        public Builder tenantId(String v)               { this.tenantId = v; return this; }
        public Builder balancingGroupId(String v)       { this.balancingGroupId = v; return this; }
        public Builder deliveryDay(LocalDate v)         { this.deliveryDay = v; return this; }
        public Builder intervalStart(Instant v)         { this.intervalStart = v; return this; }
        public Builder intervalEnd(Instant v)           { this.intervalEnd = v; return this; }
        public Builder nominatedVolumeMw(BigDecimal v)  { this.nominatedVolumeMw = v; return this; }
        public Builder nominationTimestamp(Instant v)   { this.nominationTimestamp = v; return this; }
        public Builder nominationVersion(int v)         { this.nominationVersion = v; return this; }
        public Builder submittedBy(String v)            { this.submittedBy = v; return this; }

        public NominationRecord build() {
            Objects.requireNonNull(nominationId,        "nominationId");
            Objects.requireNonNull(tenantId,            "tenantId");
            Objects.requireNonNull(balancingGroupId,    "balancingGroupId");
            Objects.requireNonNull(deliveryDay,         "deliveryDay");
            Objects.requireNonNull(intervalStart,       "intervalStart");
            Objects.requireNonNull(intervalEnd,         "intervalEnd");
            Objects.requireNonNull(nominatedVolumeMw,   "nominatedVolumeMw");
            Objects.requireNonNull(nominationTimestamp, "nominationTimestamp");
            Objects.requireNonNull(submittedBy,         "submittedBy");
            if (!intervalEnd.isAfter(intervalStart)) {
                throw new IllegalArgumentException(
                    "intervalEnd must be after intervalStart");
            }
            if (nominationVersion < 1) {
                throw new IllegalArgumentException(
                    "nominationVersion must be >= 1");
            }
            return new NominationRecord(this);
        }
    }
}
