package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.ImbalanceRecord;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Port interface for {@link ImbalanceRecord} persistence.
 *
 * <p>Imbalance records are not bitemporal. TSO corrections produce new records with an
 * incremented {@code recordVersion}; the record with the highest version per
 * {@code (tenantId, balancingGroupId, intervalStart)} is the authoritative value (DA-SET-04).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface ImbalanceRecordRepository {

    /**
     * Persist a single imbalance record.
     *
     * @param record the record to persist; must not be null
     */
    void save(ImbalanceRecord record);

    /**
     * Batch persist multiple imbalance records in a single flush.
     * Default implementation falls back to individual saves; JPA adapters
     * should override to use batched JDBC inserts with flush size 50.
     *
     * @param records the records to persist; must not be null or empty
     */
    default void saveAll(List<ImbalanceRecord> records) {
        records.forEach(this::save);
    }

    /**
     * Load all imbalance records for a balancing group on a given delivery day,
     * ordered by {@code intervalStart} ascending.
     *
     * @param tenantId         tenant identifier (D-14, Pattern #32)
     * @param balancingGroupId balancing group identifier
     * @param deliveryDay      CET-interpreted delivery day (denormalized column)
     * @return records ordered by interval start; empty list if none found
     */
    List<ImbalanceRecord> findByDeliveryDay(String tenantId, String balancingGroupId,
                                             LocalDate deliveryDay);

    /**
     * Load all imbalance records for a balancing group within a calendar month.
     * Used by the monthly aggregation path in {@code ImbalanceSettlementService}
     * (DA-SET-04).
     *
     * @param tenantId         tenant identifier (D-14, Pattern #32)
     * @param balancingGroupId balancing group identifier
     * @param month            the calendar month to aggregate
     * @return all records for the month ordered by interval start; empty list if none
     */
    List<ImbalanceRecord> findByMonth(String tenantId, String balancingGroupId, YearMonth month);
}
