package com.power.posval.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped balancing responsible party (BRP) / balancing group reference.
 * {@code activeTo} is nullable — null indicates the group is currently active.
 * Pattern #3, S4.4, DA-VOL-03.
 */
public final class BalancingGroup {

    private final UUID bgId;
    private final String tenantId;
    private final String tsoArea;
    private final String bgCode;
    private final LocalDate activeFrom;
    private final LocalDate activeTo;    // nullable

    private BalancingGroup(Builder b) {
        this.bgId       = b.bgId;
        this.tenantId   = b.tenantId;
        this.tsoArea    = b.tsoArea;
        this.bgCode     = b.bgCode;
        this.activeFrom = b.activeFrom;
        this.activeTo   = b.activeTo;
    }

    public static Builder builder() { return new Builder(); }

    public UUID bgId()          { return bgId; }
    public String tenantId()    { return tenantId; }
    public String tsoArea()     { return tsoArea; }
    public String bgCode()      { return bgCode; }
    public LocalDate activeFrom() { return activeFrom; }
    public LocalDate activeTo() { return activeTo; }

    /** Returns true when this group is currently open-ended (no expiry date). */
    public boolean isOpenEnded() { return activeTo == null; }

    public static final class Builder {
        private UUID bgId;
        private String tenantId;
        private String tsoArea;
        private String bgCode;
        private LocalDate activeFrom;
        private LocalDate activeTo;

        public Builder bgId(UUID v)           { this.bgId = v; return this; }
        public Builder tenantId(String v)     { this.tenantId = v; return this; }
        public Builder tsoArea(String v)      { this.tsoArea = v; return this; }
        public Builder bgCode(String v)       { this.bgCode = v; return this; }
        public Builder activeFrom(LocalDate v) { this.activeFrom = v; return this; }
        public Builder activeTo(LocalDate v)  { this.activeTo = v; return this; }

        public BalancingGroup build() {
            Objects.requireNonNull(bgId,       "bgId");
            Objects.requireNonNull(tenantId,   "tenantId");
            Objects.requireNonNull(tsoArea,    "tsoArea");
            Objects.requireNonNull(bgCode,     "bgCode");
            Objects.requireNonNull(activeFrom, "activeFrom");
            if (tsoArea.isBlank()) {
                throw new IllegalArgumentException("tsoArea must not be blank");
            }
            if (bgCode.isBlank()) {
                throw new IllegalArgumentException("bgCode must not be blank");
            }
            return new BalancingGroup(this);
        }
    }
}
