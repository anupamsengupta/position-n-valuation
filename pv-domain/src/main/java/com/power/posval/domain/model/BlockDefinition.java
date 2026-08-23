package com.power.posval.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * System-level (tenant-independent) configurable block type definition per exchange.
 * Defines the CET time window and applicable-day mask used by the block order
 * decomposition engine (DA-VOL-02).
 * {@code effectiveTo} is nullable — null indicates open-ended (currently active).
 * {@code biddingZone} is nullable — null means the definition applies to all zones
 * for this exchange.
 * Pattern #3, S4.4, DA-VOL-02.
 */
public final class BlockDefinition {

    private final UUID blockId;
    private final String exchange;
    private final BlockType blockType;
    private final String biddingZone;      // nullable
    private final LocalTime startHour;
    private final LocalTime endHour;
    private final String applicableDays;   // encoded day-of-week mask, e.g. "MON-FRI"
    private final String holidayCalendarRef; // nullable
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveTo;   // nullable

    private BlockDefinition(Builder b) {
        this.blockId            = b.blockId;
        this.exchange           = b.exchange;
        this.blockType          = b.blockType;
        this.biddingZone        = b.biddingZone;
        this.startHour          = b.startHour;
        this.endHour            = b.endHour;
        this.applicableDays     = b.applicableDays;
        this.holidayCalendarRef = b.holidayCalendarRef;
        this.effectiveFrom      = b.effectiveFrom;
        this.effectiveTo        = b.effectiveTo;
    }

    public static Builder builder() { return new Builder(); }

    public UUID blockId()               { return blockId; }
    public String exchange()            { return exchange; }
    public BlockType blockType()        { return blockType; }
    public String biddingZone()         { return biddingZone; }
    public LocalTime startHour()        { return startHour; }
    public LocalTime endHour()          { return endHour; }
    public String applicableDays()      { return applicableDays; }
    public String holidayCalendarRef()  { return holidayCalendarRef; }
    public LocalDate effectiveFrom()    { return effectiveFrom; }
    public LocalDate effectiveTo()      { return effectiveTo; }

    /** Returns true when this definition is open-ended (no expiry). */
    public boolean isOpenEnded() { return effectiveTo == null; }

    public static final class Builder {
        private UUID blockId;
        private String exchange;
        private BlockType blockType;
        private String biddingZone;
        private LocalTime startHour;
        private LocalTime endHour;
        private String applicableDays;
        private String holidayCalendarRef;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        public Builder blockId(UUID v)              { this.blockId = v; return this; }
        public Builder exchange(String v)           { this.exchange = v; return this; }
        public Builder blockType(BlockType v)       { this.blockType = v; return this; }
        public Builder biddingZone(String v)        { this.biddingZone = v; return this; }
        public Builder startHour(LocalTime v)       { this.startHour = v; return this; }
        public Builder endHour(LocalTime v)         { this.endHour = v; return this; }
        public Builder applicableDays(String v)     { this.applicableDays = v; return this; }
        public Builder holidayCalendarRef(String v) { this.holidayCalendarRef = v; return this; }
        public Builder effectiveFrom(LocalDate v)   { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(LocalDate v)     { this.effectiveTo = v; return this; }

        public BlockDefinition build() {
            Objects.requireNonNull(blockId,       "blockId");
            Objects.requireNonNull(exchange,      "exchange");
            Objects.requireNonNull(blockType,     "blockType");
            Objects.requireNonNull(startHour,     "startHour");
            Objects.requireNonNull(endHour,       "endHour");
            Objects.requireNonNull(applicableDays, "applicableDays");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            if (applicableDays.isBlank()) {
                throw new IllegalArgumentException("applicableDays must not be blank");
            }
            return new BlockDefinition(this);
        }
    }
}
