package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.PositionMonthSummary;
import com.power.posval.domain.model.SettlementCell;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port interface for settlement cell persistence (S5a).
 * Pattern #18, FR-070, FR-071.
 */
public interface SettlementCellRepository {

    /** Persist a settlement cell (bitemporal). */
    void save(SettlementCell cell);

    /**
     * Batch persist multiple settlement cells in a single flush.
     * Default implementation falls back to individual saves; JPA adapters
     * should override to use batched JDBC inserts.
     */
    default void saveAll(List<SettlementCell> cells) {
        cells.forEach(this::save);
    }

    /** Find current-knowledge settlement cells for a position within a range. */
    List<SettlementCell> findByPosition(String tenantId, UUID positionId,
                                         Instant rangeStart, Instant rangeEnd);

    /**
     * Delete settlement cells for a position whose intervalStart falls within [start, end).
     * Used by revaluation to replace stale cells with fresh computations.
     * @return number of cells deleted
     */
    default int deleteByPositionAndInterval(String tenantId, UUID positionId,
                                             Instant intervalStart, Instant intervalEnd) {
        throw new UnsupportedOperationException("deleteByPositionAndInterval not implemented");
    }

    /**
     * Aggregate settlement cells per position × delivery-month for a tenant
     * within a delivery range. Computes: totalMwh (sum), avgMw (TWA),
     * totalAmount (sum), totalMarketAmount (sum), totalPnl (sum),
     * avgPrice (volume-weighted), avgMarketPrice (volume-weighted).
     * FR-035, FR-090.
     */
    default List<PositionMonthSummary> findMonthlySummary(String tenantId,
                                                            Instant rangeStart,
                                                            Instant rangeEnd) {
        throw new UnsupportedOperationException("findMonthlySummary not implemented");
    }

    /**
     * Aggregate settlement cells for a single position within a delivery range.
     * Returns one PositionMonthSummary per delivery month.
     */
    default List<PositionMonthSummary> findMonthlySummaryByPosition(String tenantId,
                                                                      UUID positionId,
                                                                      Instant rangeStart,
                                                                      Instant rangeEnd) {
        throw new UnsupportedOperationException("findMonthlySummaryByPosition not implemented");
    }

    /**
     * Delete all settlement cells for a position.
     * Used during trade supersession to clean up old position's cells.
     * @return number of cells deleted
     */
    default int deleteByPositionId(String tenantId, UUID positionId) {
        throw new UnsupportedOperationException("deleteByPositionId not implemented");
    }

    /** Check if any settlement cells exist for a given position (idempotency check). */
    default boolean existsByPositionId(String tenantId, UUID positionId) {
        return !findByPosition(tenantId, positionId,
                Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).isEmpty();
    }

    /**
     * Bulk-fetch settlement cells for multiple positions within a range.
     *
     * <p>Replaces per-position {@link #findByPosition} N+1 pattern for L3 queries.
     * The adapter issues a single SQL query with a {@code position_id IN (...)}
     * clause. For >100 position IDs, the adapter batches into chunks of 100 to
     * avoid PostgreSQL parameter limits.
     *
     * <p>The service groups the returned cells by {@code positionId} in application
     * memory and applies FR-035 aggregation (TWA for MW, sum for MWh/amounts,
     * volume-weighted avg for prices) per position.
     *
     * <p>Pattern #18 (Repository Port + Adapter), §5.4a.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param positionIds position identifiers (may be empty — returns empty list)
     * @param rangeStart  interval range start (UTC, inclusive)
     * @param rangeEnd    interval range end (UTC, exclusive)
     * @return all settlement cells for the given positions in the range,
     *         ordered by positionId then intervalStart
     */
    default List<SettlementCell> findByPositionIds(String tenantId,
                                                    List<UUID> positionIds,
                                                    Instant rangeStart,
                                                    Instant rangeEnd) {
        throw new UnsupportedOperationException("findByPositionIds not implemented");
    }
}
