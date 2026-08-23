package com.power.posval.persistence.entity;

import com.power.posval.domain.model.BalancingGroup;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code balancing_group} table.
 * Tenant-scoped balancing responsible party (BRP) / balancing group reference.
 * UNIQUE constraint on (tenant_id, bg_code) ensures one active code per tenant.
 * {@code active_to} nullable = open-ended (currently active).
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-VOL-03.
 */
@Entity
@Table(name = "balancing_group", schema = "da",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_bg_tenant_code", columnNames = {"tenant_id", "bg_code"})
    })
public class BalancingGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bg_seq")
    @SequenceGenerator(name = "bg_seq",
                       sequenceName = "da.balancing_group_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "bg_id", nullable = false, unique = true)
    private UUID bgId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "tso_area", nullable = false, length = 32)
    private String tsoArea;

    @Column(name = "bg_code", nullable = false, length = 64)
    private String bgCode;

    @Column(name = "active_from", nullable = false)
    private LocalDate activeFrom;

    /** Nullable — null indicates the group is currently active (open-ended). */
    @Column(name = "active_to")
    private LocalDate activeTo;

    // --- JPA ---

    protected BalancingGroupEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getBgId() { return bgId; }
    public void setBgId(UUID bgId) { this.bgId = bgId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTsoArea() { return tsoArea; }
    public void setTsoArea(String tsoArea) { this.tsoArea = tsoArea; }

    public String getBgCode() { return bgCode; }
    public void setBgCode(String bgCode) { this.bgCode = bgCode; }

    public LocalDate getActiveFrom() { return activeFrom; }
    public void setActiveFrom(LocalDate activeFrom) { this.activeFrom = activeFrom; }

    public LocalDate getActiveTo() { return activeTo; }
    public void setActiveTo(LocalDate activeTo) { this.activeTo = activeTo; }

    // --- Domain conversion ---

    public BalancingGroup toDomain() {
        return BalancingGroup.builder()
            .bgId(this.bgId)
            .tenantId(this.tenantId)
            .tsoArea(this.tsoArea)
            .bgCode(this.bgCode)
            .activeFrom(this.activeFrom)
            .activeTo(this.activeTo)
            .build();
    }

    public static BalancingGroupEntity fromDomain(BalancingGroup d) {
        BalancingGroupEntity e = new BalancingGroupEntity();
        e.setBgId(d.bgId());
        e.setTenantId(d.tenantId());
        e.setTsoArea(d.tsoArea());
        e.setBgCode(d.bgCode());
        e.setActiveFrom(d.activeFrom());
        e.setActiveTo(d.activeTo());
        return e;
    }
}
