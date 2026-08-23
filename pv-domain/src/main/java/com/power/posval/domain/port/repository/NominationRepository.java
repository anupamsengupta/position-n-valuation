package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.NominationRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port interface for {@link NominationRecord} persistence.
 *
 * <p>Nominations are not bitemporal. The latest version per
 * {@code (tenantId, balancingGroupId, intervalStart)} is the authoritative record.
 * Versioning is monotonic via {@code nominationVersion} (DA-VOL-03).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface NominationRepository {

    /**
     * Persist a single nomination record.
     *
     * @param record the record to persist; must not be null
     */
    void save(NominationRecord record);

    /**
     * Batch persist multiple nomination records in a single flush.
     * Default implementation falls back to individual saves; JPA adapters
     * should override to use batched JDBC inserts with flush size 50.
     *
     * @param records the records to persist; must not be null or empty
     */
    default void saveAll(List<NominationRecord> records) {
        records.forEach(this::save);
    }

    /**
     * Load all nominations for a balancing group on a given delivery day,
     * ordered by {@code intervalStart} ascending.
     *
     * @param tenantId         tenant identifier (D-14, Pattern #32)
     * @param balancingGroupId balancing group identifier
     * @param deliveryDay      CET-interpreted delivery day
     * @return nominations ordered by interval start; empty list if none found
     */
    List<NominationRecord> findByDeliveryDay(String tenantId, String balancingGroupId,
                                              LocalDate deliveryDay);

    /**
     * Load the latest nomination version for a specific interval within a balancing group.
     * "Latest" is defined as the record with the highest {@code nominationVersion}.
     *
     * @param tenantId         tenant identifier (D-14, Pattern #32)
     * @param balancingGroupId balancing group identifier
     * @param intervalStart    UTC interval start instant
     * @return the latest nomination record for the interval, if any
     */
    Optional<NominationRecord> findLatestByInterval(String tenantId, String balancingGroupId,
                                                     Instant intervalStart);
}
