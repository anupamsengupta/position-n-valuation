package com.power.posval.persistence.entity;

import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * JPA entity for the {@code block_definition} table.
 * System-level (tenant-independent) configurable block type definition per exchange.
 * Defines the CET time window and applicable-day mask for block order decomposition (DA-VOL-02).
 * {@code bidding_zone} and {@code holiday_calendar_ref} are nullable.
 * {@code effective_to} nullable = open-ended.
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1.
 */
@Entity
@Table(name = "block_definition", schema = "da")
public class BlockDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bd_seq")
    @SequenceGenerator(name = "bd_seq",
                       sequenceName = "da.block_definition_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "block_id", nullable = false, unique = true)
    private UUID blockId;

    @Column(name = "exchange", nullable = false, length = 32)
    private String exchange;

    @Column(name = "block_type", nullable = false, length = 16)
    private String blockType;

    /** Nullable — null means applies to all zones for this exchange. */
    @Column(name = "bidding_zone", length = 16)
    private String biddingZone;

    @Column(name = "start_hour", nullable = false)
    private LocalTime startHour;

    @Column(name = "end_hour", nullable = false)
    private LocalTime endHour;

    @Column(name = "applicable_days", nullable = false, length = 32)
    private String applicableDays;

    /** Nullable reference to HolidayCalendar zone. */
    @Column(name = "holiday_calendar_ref", length = 8)
    private String holidayCalendarRef;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Nullable — null indicates open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // --- JPA ---

    protected BlockDefinitionEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getBlockId() { return blockId; }
    public void setBlockId(UUID blockId) { this.blockId = blockId; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getBlockType() { return blockType; }
    public void setBlockType(String blockType) { this.blockType = blockType; }

    public String getBiddingZone() { return biddingZone; }
    public void setBiddingZone(String biddingZone) { this.biddingZone = biddingZone; }

    public LocalTime getStartHour() { return startHour; }
    public void setStartHour(LocalTime startHour) { this.startHour = startHour; }

    public LocalTime getEndHour() { return endHour; }
    public void setEndHour(LocalTime endHour) { this.endHour = endHour; }

    public String getApplicableDays() { return applicableDays; }
    public void setApplicableDays(String applicableDays) { this.applicableDays = applicableDays; }

    public String getHolidayCalendarRef() { return holidayCalendarRef; }
    public void setHolidayCalendarRef(String holidayCalendarRef) { this.holidayCalendarRef = holidayCalendarRef; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    // --- Domain conversion ---

    public BlockDefinition toDomain() {
        return BlockDefinition.builder()
            .blockId(this.blockId)
            .exchange(this.exchange)
            .blockType(BlockType.valueOf(this.blockType))
            .biddingZone(this.biddingZone)
            .startHour(this.startHour)
            .endHour(this.endHour)
            .applicableDays(this.applicableDays)
            .holidayCalendarRef(this.holidayCalendarRef)
            .effectiveFrom(this.effectiveFrom)
            .effectiveTo(this.effectiveTo)
            .build();
    }

    public static BlockDefinitionEntity fromDomain(BlockDefinition d) {
        BlockDefinitionEntity e = new BlockDefinitionEntity();
        e.setBlockId(d.blockId());
        e.setExchange(d.exchange());
        e.setBlockType(d.blockType().name());
        e.setBiddingZone(d.biddingZone());
        e.setStartHour(d.startHour());
        e.setEndHour(d.endHour());
        e.setApplicableDays(d.applicableDays());
        e.setHolidayCalendarRef(d.holidayCalendarRef());
        e.setEffectiveFrom(d.effectiveFrom());
        e.setEffectiveTo(d.effectiveTo());
        return e;
    }
}
