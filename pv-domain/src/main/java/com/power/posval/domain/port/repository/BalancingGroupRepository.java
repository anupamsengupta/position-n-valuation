package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.BalancingGroup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for {@link BalancingGroup} persistence and lookup.
 *
 * <p>Balancing groups are tenant-scoped (each tenant has its own registered BRP codes).
 * All methods therefore require a {@code tenantId} parameter (D-14, Pattern #32).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface BalancingGroupRepository {

    /**
     * Load a balancing group by its UUID business key within a tenant.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param bgId     UUID business key of the balancing group
     * @return the balancing group, if found
     */
    Optional<BalancingGroup> findById(String tenantId, UUID bgId);

    /**
     * Load a balancing group by its TSO-assigned code within a tenant.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param bgCode   TSO-assigned balancing group code
     * @return the balancing group with this code, if found
     */
    Optional<BalancingGroup> findByCode(String tenantId, String bgCode);

    /**
     * Load all currently active balancing groups for a tenant.
     * "Active" means {@code activeTo IS NULL OR activeTo > today()}.
     * Used by the nomination service to validate incoming nomination commands.
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @return all active balancing groups ordered by {@code bgCode}; empty list if none
     */
    List<BalancingGroup> findActive(String tenantId);
}
