package com.power.posval.persistence.entity;

import com.power.posval.domain.model.AssetToBalancingGroupMapping;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code asset_to_bg_mapping} table.
 * Tenant-scoped effective-dated mapping from a physical delivery point (asset)
 * to a balancing group.
 * {@code effective_to} nullable = open-ended (currently active mapping).
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-VOL-03.
 */
@Entity
@Table(name = "asset_to_bg_mapping", schema = "da",
    indexes = {
        @Index(name = "idx_abgm_tenant_dp_from",
               columnList = "tenant_id, delivery_point_id, effective_from")
    })
public class AssetToBalancingGroupMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "abgm_seq")
    @SequenceGenerator(name = "abgm_seq",
                       sequenceName = "da.asset_to_bg_mapping_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "mapping_id", nullable = false, unique = true)
    private UUID mappingId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "delivery_point_id", nullable = false, length = 64)
    private String deliveryPointId;

    @Column(name = "balancing_group_id", nullable = false)
    private UUID balancingGroupId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Nullable — null means the mapping has no expiry date (currently active). */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // --- JPA ---

    protected AssetToBalancingGroupMappingEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getMappingId() { return mappingId; }
    public void setMappingId(UUID mappingId) { this.mappingId = mappingId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDeliveryPointId() { return deliveryPointId; }
    public void setDeliveryPointId(String deliveryPointId) { this.deliveryPointId = deliveryPointId; }

    public UUID getBalancingGroupId() { return balancingGroupId; }
    public void setBalancingGroupId(UUID balancingGroupId) { this.balancingGroupId = balancingGroupId; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    // --- Domain conversion ---

    /** Convert this entity to the domain model. DA-VOL-03, S4.4. */
    public AssetToBalancingGroupMapping toDomain() {
        return AssetToBalancingGroupMapping.builder()
            .mappingId(this.mappingId)
            .tenantId(this.tenantId)
            .deliveryPointId(this.deliveryPointId)
            .balancingGroupId(this.balancingGroupId)
            .effectiveFrom(this.effectiveFrom)
            .effectiveTo(this.effectiveTo)
            .build();
    }

    /** Factory: create entity from domain model. */
    public static AssetToBalancingGroupMappingEntity fromDomain(AssetToBalancingGroupMapping d) {
        AssetToBalancingGroupMappingEntity e = new AssetToBalancingGroupMappingEntity();
        e.setMappingId(d.mappingId());
        e.setTenantId(d.tenantId());
        e.setDeliveryPointId(d.deliveryPointId());
        e.setBalancingGroupId(d.balancingGroupId());
        e.setEffectiveFrom(d.effectiveFrom());
        e.setEffectiveTo(d.effectiveTo());
        return e;
    }
}
