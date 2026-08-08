package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.repository.DependencyEdge;
import com.power.posval.domain.port.repository.DependencyIndex;
import com.power.posval.domain.service.PrunePolicy;
import com.power.posval.domain.service.HotStoreRetentionPolicy;
import com.power.posval.domain.service.SettlementHandoverPolicy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JPA adapter for DependencyIndex. §13.2, Pattern #18.
 * Edge upsert/prune/query for blast-radius optimization.
 */
public class JpaDependencyIndex implements DependencyIndex {

    private final Provider<EntityManager> emProvider;

    @Inject
    public JpaDependencyIndex(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    @Override
    public void upsert(DependencyEdge edge) {
        emProvider.get()
            .createNativeQuery("""
                INSERT INTO valuation.dependency_edge
                    (tenant_id, cell_id, cell_type, input_series_key, input_type,
                     affected_range_start, affected_range_end, active_leaves, created_at)
                VALUES (:tenantId, :cellId, :cellType, :inputSeriesKey, :inputType,
                        :rangeStart, :rangeEnd, CAST(:activeLeaves AS jsonb), :createdAt)
                ON CONFLICT (tenant_id, cell_id, input_series_key)
                DO UPDATE SET
                    active_leaves = CAST(:activeLeaves AS jsonb),
                    affected_range_start = :rangeStart,
                    affected_range_end = :rangeEnd,
                    pruned_at = NULL
                """)
            .setParameter("tenantId", edge.tenantId())
            .setParameter("cellId", edge.cellId().toString())
            .setParameter("cellType", edge.cellType())
            .setParameter("inputSeriesKey", edge.inputSeriesKey())
            .setParameter("inputType", edge.inputType())
            .setParameter("rangeStart", edge.affectedRange().startInstant().toInstant())
            .setParameter("rangeEnd", edge.affectedRange().endInstant().toInstant())
            .setParameter("activeLeaves", edge.activeLeaves().toString())
            .setParameter("createdAt", edge.createdAt())
            .executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DependencyEdge> findAffectedCells(String tenantId,
                                                    String inputSeriesKey,
                                                    DeliveryRange affectedRange,
                                                    String activeLeafFilter) {
        String sql = """
            SELECT tenant_id, cell_id, cell_type, input_series_key, input_type,
                   affected_range_start, affected_range_end, active_leaves,
                   created_at, pruned_at
            FROM valuation.dependency_edge
            WHERE tenant_id = :tenantId
              AND input_series_key = :inputSeriesKey
              AND affected_range_start < :rangeEnd
              AND affected_range_end > :rangeStart
              AND pruned_at IS NULL
            """;

        if (activeLeafFilter != null) {
            sql += " AND active_leaves @> CAST(:leafFilter AS jsonb)";
        }

        var query = emProvider.get().createNativeQuery(sql)
            .setParameter("tenantId", tenantId)
            .setParameter("inputSeriesKey", inputSeriesKey)
            .setParameter("rangeStart", affectedRange.startInstant().toInstant())
            .setParameter("rangeEnd", affectedRange.endInstant().toInstant());

        if (activeLeafFilter != null) {
            query.setParameter("leafFilter", "[\"" + activeLeafFilter + "\"]");
        }

        return query.getResultList().stream()
            .map(row -> mapToEdge((Object[]) row, affectedRange))
            .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> findAffectedPositionIds(String tenantId,
                                               String inputSeriesKey,
                                               Instant rangeStart,
                                               Instant rangeEnd) {
        return emProvider.get().createNativeQuery("""
                SELECT DISTINCT sc.position_id
                FROM valuation.dependency_edge de
                JOIN valuation.settlement_cell sc ON sc.cell_id = de.cell_id
                WHERE de.tenant_id = :tenantId
                  AND de.input_series_key = :inputSeriesKey
                  AND de.affected_range_start < :rangeEnd
                  AND de.affected_range_end > :rangeStart
                  AND de.pruned_at IS NULL
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("inputSeriesKey", inputSeriesKey)
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd)
            .getResultList()
            .stream()
            .map(row -> UUID.fromString(row.toString()))
            .toList();
    }

    @Override
    public void prune(String tenantId, PrunePolicy policy) {
        Instant now = Instant.now();

        switch (policy) {
            case SettlementHandoverPolicy shp -> emProvider.get()
                .createNativeQuery("""
                    UPDATE valuation.dependency_edge
                    SET pruned_at = :now
                    WHERE tenant_id = :tenantId
                      AND cell_type = 'FORWARD'
                      AND created_at < :cutoff
                      AND pruned_at IS NULL
                    """)
                .setParameter("now", now)
                .setParameter("tenantId", tenantId)
                .setParameter("cutoff", shp.settlementCutoff())
                .executeUpdate();

            case HotStoreRetentionPolicy hrp -> emProvider.get()
                .createNativeQuery("""
                    UPDATE valuation.dependency_edge
                    SET pruned_at = :now
                    WHERE tenant_id = :tenantId
                      AND affected_range_end < :threshold
                      AND pruned_at IS NULL
                    """)
                .setParameter("now", now)
                .setParameter("tenantId", tenantId)
                .setParameter("threshold",
                    hrp.oldestRetainedMonth().atDay(1).atStartOfDay()
                        .toInstant(java.time.ZoneOffset.UTC))
                .executeUpdate();
        }
    }

    private DependencyEdge mapToEdge(Object[] row, DeliveryRange range) {
        return new DependencyEdge(
            (String) row[0],                                                // tenantId
            java.util.UUID.fromString(row[1].toString()),                   // cellId (UUID)
            (String) row[2],                                                // cellType
            (String) row[3],                                                // inputSeriesKey
            (String) row[4],                                                // inputType
            range,                                                          // affectedRange
            Set.of(),                                                       // activeLeaves
            ((java.sql.Timestamp) row[8]).toInstant(),                      // createdAt
            row[9] != null ? ((java.sql.Timestamp) row[9]).toInstant() : null); // prunedAt
    }
}
