package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped, effective-dated exchange fee schedule entry.
 * {@code ratePerMwh} is stored at PRICE scale (8 decimal places, per NumericPrecision).
 * {@code memberTier} is nullable — null means the rate applies to the default member tier.
 * {@code effectiveTo} is nullable — null means the schedule is currently active (open-ended).
 * Pattern #3, S4.4, DA-SET-03.
 */
public final class ExchangeFeeSchedule {

    private final UUID scheduleId;
    private final String tenantId;
    private final String exchange;
    private final String feeType;
    private final BigDecimal ratePerMwh;
    private final String memberTier;     // nullable
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveTo; // nullable

    private ExchangeFeeSchedule(Builder b) {
        this.scheduleId   = b.scheduleId;
        this.tenantId     = b.tenantId;
        this.exchange     = b.exchange;
        this.feeType      = b.feeType;
        this.ratePerMwh   = b.ratePerMwh;
        this.memberTier   = b.memberTier;
        this.effectiveFrom = b.effectiveFrom;
        this.effectiveTo  = b.effectiveTo;
    }

    public static Builder builder() { return new Builder(); }

    public UUID scheduleId()          { return scheduleId; }
    public String tenantId()          { return tenantId; }
    public String exchange()          { return exchange; }
    public String feeType()           { return feeType; }
    public BigDecimal ratePerMwh()    { return ratePerMwh; }
    public String memberTier()        { return memberTier; }
    public LocalDate effectiveFrom()  { return effectiveFrom; }
    public LocalDate effectiveTo()    { return effectiveTo; }

    /** Returns true when this schedule has no expiry date. */
    public boolean isOpenEnded() { return effectiveTo == null; }

    public static final class Builder {
        private UUID scheduleId;
        private String tenantId;
        private String exchange;
        private String feeType;
        private BigDecimal ratePerMwh;
        private String memberTier;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        public Builder scheduleId(UUID v)         { this.scheduleId = v; return this; }
        public Builder tenantId(String v)         { this.tenantId = v; return this; }
        public Builder exchange(String v)         { this.exchange = v; return this; }
        public Builder feeType(String v)          { this.feeType = v; return this; }
        public Builder ratePerMwh(BigDecimal v)   { this.ratePerMwh = v; return this; }
        public Builder memberTier(String v)       { this.memberTier = v; return this; }
        public Builder effectiveFrom(LocalDate v) { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(LocalDate v)   { this.effectiveTo = v; return this; }

        public ExchangeFeeSchedule build() {
            Objects.requireNonNull(scheduleId,   "scheduleId");
            Objects.requireNonNull(tenantId,     "tenantId");
            Objects.requireNonNull(exchange,     "exchange");
            Objects.requireNonNull(feeType,      "feeType");
            Objects.requireNonNull(ratePerMwh,   "ratePerMwh");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            if (exchange.isBlank()) {
                throw new IllegalArgumentException("exchange must not be blank");
            }
            if (feeType.isBlank()) {
                throw new IllegalArgumentException("feeType must not be blank");
            }
            if (ratePerMwh.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("ratePerMwh must be non-negative");
            }
            return new ExchangeFeeSchedule(this);
        }
    }
}
