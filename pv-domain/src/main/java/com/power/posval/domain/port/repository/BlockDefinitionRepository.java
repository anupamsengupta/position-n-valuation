package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port interface for {@link BlockDefinition} read access.
 *
 * <p>Block definitions are system-level reference data — tenant-independent because they
 * reflect exchange-published block product definitions, not per-tenant configuration
 * (S5.1 note). No {@code tenantId} parameter appears on any method.
 *
 * <p>Used by the block order decomposition engine (DA-VOL-02) to resolve the CET
 * time window and applicable-day mask for a given block type on a given exchange.
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface BlockDefinitionRepository {

    /**
     * Find the effective block definition for a specific exchange, block type, bidding zone,
     * and delivery date.
     *
     * <p>A definition is applicable when:
     * {@code effectiveFrom <= deliveryDate AND (effectiveTo IS NULL OR effectiveTo > deliveryDate)}.
     *
     * <p>If {@code biddingZone} is {@code null}, this method attempts to find a zone-independent
     * definition (i.e. one where {@code biddingZone IS NULL}).
     *
     * @param exchange     exchange code, e.g. {@code "EPEX_SPOT"}
     * @param blockType    the block type to look up
     * @param biddingZone  the bidding zone, or {@code null} for the zone-independent definition
     * @param deliveryDate the date for which to resolve the effective definition
     * @return the effective block definition, if one exists
     */
    Optional<BlockDefinition> findEffective(String exchange, BlockType blockType,
                                             String biddingZone, LocalDate deliveryDate);

    /**
     * Load all effective block definitions for an exchange on a given delivery date.
     * Returns all block types across all bidding zones (and zone-independent entries).
     * Used by the decomposition engine to bulk-load reference data once per import session.
     *
     * @param exchange     exchange code, e.g. {@code "EPEX_SPOT"}
     * @param deliveryDate the date for which to resolve effective definitions
     * @return all effective definitions for the exchange; empty list if none
     */
    List<BlockDefinition> findAllEffective(String exchange, LocalDate deliveryDate);
}
