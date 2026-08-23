package com.power.posval.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped mapping from a physical delivery point (asset) to a balancing group.
 * Effective-dated to support re-mapping over time.
 * {@code effectiveTo} is nullable — null means the mapping is currently active.
 * Pattern #3, S4.4, DA-VOL-03.
 */
public final class AssetToBalancingGroupMapping {

    private final UUID mappingId;
    private final String tenantId;
    private final String deliveryPointId;
    private final UUID balancingGroupId;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveTo;   // nullable

    private AssetToBalancingGroupMapping(Builder b) {
        this.mappingId        = b.mappingId;
        this.tenantId         = b.tenantId;
        this.deliveryPointId  = b.deliveryPointId;
        this.balancingGroupId = b.balancingGroupId;
        this.effectiveFrom    = b.effectiveFrom;
        this.effectiveTo      = b.effectiveTo;
    }

    public static Builder builder() { return new Builder(); }

    public UUID mappingId()           { return mappingId; }
    public String tenantId()          { return tenantId; }
    public String deliveryPointId()   { return deliveryPointId; }
    public UUID balancingGroupId()    { return balancingGroupId; }
    public LocalDate effectiveFrom()  { return effectiveFrom; }
    public LocalDate effectiveTo()    { return effectiveTo; }

    /** Returns true when this mapping has no expiry date (currently active). */
    public boolean isOpenEnded() { return effectiveTo == null; }

    public static final class Builder {
        private UUID mappingId;
        private String tenantId;
        private String deliveryPointId;
        private UUID balancingGroupId;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        public Builder mappingId(UUID v)            { this.mappingId = v; return this; }
        public Builder tenantId(String v)           { this.tenantId = v; return this; }
        public Builder deliveryPointId(String v)    { this.deliveryPointId = v; return this; }
        public Builder balancingGroupId(UUID v)     { this.balancingGroupId = v; return this; }
        public Builder effectiveFrom(LocalDate v)   { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(LocalDate v)     { this.effectiveTo = v; return this; }

        public AssetToBalancingGroupMapping build() {
            Objects.requireNonNull(mappingId,       "mappingId");
            Objects.requireNonNull(tenantId,        "tenantId");
            Objects.requireNonNull(deliveryPointId, "deliveryPointId");
            Objects.requireNonNull(balancingGroupId, "balancingGroupId");
            Objects.requireNonNull(effectiveFrom,   "effectiveFrom");
            if (deliveryPointId.isBlank()) {
                throw new IllegalArgumentException("deliveryPointId must not be blank");
            }
            return new AssetToBalancingGroupMapping(this);
        }
    }
}
