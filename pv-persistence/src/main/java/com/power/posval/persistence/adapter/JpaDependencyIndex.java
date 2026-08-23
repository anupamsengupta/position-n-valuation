package com.power.posval.persistence.adapter;

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
            .setParameter("cellId", edge.cellId())
            .setParameter("cellType", edge.cellType())
            .setParameter("inputSeriesKey", edge.inputSeriesKey())
            .setParameter("inputType", edge.inputType())
            .setParameter("rangeStart", edge.affectedRangeStart())
            .setParameter("rangeEnd", edge.affectedRangeEnd())
            .setParameter("activeLeaves", toJsonArray(edge.activeLeaves()))
            .setParameter("createdAt", edge.createdAt())
            .executeUpdate();
    }

    @Override
    public void upsertAll(List<DependencyEdge> edges) {
        if (edges.isEmpty()) return;
        EntityManager em = emProvider.get();
        em.unwrap(org.hibernate.Session.class).doWork(connection -> {
            try (var ps = connection.prepareStatement("""
                    INSERT INTO valuation.dependency_edge
                        (tenant_id, cell_id, cell_type, input_series_key, input_type,
                         affected_range_start, affected_range_end, active_leaves, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    ON CONFLICT (tenant_id, cell_id, input_series_key)
                    DO UPDATE SET
                        active_leaves = CAST(EXCLUDED.active_leaves AS jsonb),
                        affected_range_start = EXCLUDED.affected_range_start,
                        affected_range_end = EXCLUDED.affected_range_end,
                        pruned_at = NULL
                    """)) {
                int count = 0;
                for (DependencyEdge edge : edges) {
                    ps.setString(1, edge.tenantId());
                    ps.setObject(2, edge.cellId());
                    ps.setString(3, edge.cellType());
                    ps.setString(4, edge.inputSeriesKey());
                    ps.setString(5, edge.inputType());
                    ps.setTimestamp(6, java.sql.Timestamp.from(edge.affectedRangeStart()));
                    ps.setTimestamp(7, java.sql.Timestamp.from(edge.affectedRangeEnd()));
                    ps.setString(8, toJsonArray(edge.activeLeaves()));
                    ps.setTimestamp(9, java.sql.Timestamp.from(edge.createdAt()));
                    ps.addBatch();
                    if (++count % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                if (count % 500 != 0) {
                    ps.executeBatch();
                }
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DependencyEdge> findAffectedCells(String tenantId,
                                                    String inputSeriesKey,
                                                    Instant rangeStart,
                                                    Instant rangeEnd,
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
            .setParameter("rangeStart", rangeStart)
            .setParameter("rangeEnd", rangeEnd);

        if (activeLeafFilter != null) {
            query.setParameter("leafFilter", "[\"" + activeLeafFilter + "\"]");
        }

        return query.getResultList().stream()
            .map(row -> mapToEdge((Object[]) row))
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
                JOIN valuation.settlement_cell sc ON sc.cell_uuid = de.cell_id
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
    public int deleteByCellPosition(String tenantId, UUID positionId) {
        return emProvider.get()
            .createNativeQuery("""
                DELETE FROM valuation.dependency_edge de
                USING valuation.settlement_cell sc
                WHERE de.cell_id = sc.cell_uuid
                  AND sc.tenant_id = :tenantId
                  AND sc.position_id = :positionId
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("positionId", positionId)
            .executeUpdate();
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

    /** Serializes a Set<String> to a valid JSON array, e.g. ["a","b"]. */
    private static String toJsonArray(Set<String> leaves) {
        if (leaves == null || leaves.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        boolean first = true;
        for (String leaf : leaves) {
            if (!first) sb.append(',');
            sb.append('"').append(leaf.replace("\"", "\\\"")).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }

    /** Parses a JSON array string like ["a","b"] back into a Set<String>. */
    private static Set<String> parseJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return Set.of();
        }
        String trimmed = json.trim();
        // Strip surrounding brackets
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return Set.of();
        var result = new java.util.HashSet<String>();
        for (String token : inner.split(",")) {
            String t = token.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) {
                t = t.substring(1, t.length() - 1);
            }
            if (!t.isEmpty()) result.add(t);
        }
        return Set.copyOf(result);
    }

    private DependencyEdge mapToEdge(Object[] row) {
        return new DependencyEdge(
            (String) row[0],                                                // tenantId
            java.util.UUID.fromString(row[1].toString()),                   // cellId (UUID)
            (String) row[2],                                                // cellType
            (String) row[3],                                                // inputSeriesKey
            (String) row[4],                                                // inputType
            toInstant(row[5]),                                              // affectedRangeStart
            toInstant(row[6]),                                              // affectedRangeEnd
            parseJsonArray(row[7] != null ? row[7].toString() : "[]"),     // activeLeaves
            toInstant(row[8]),                                              // createdAt
            row[9] != null ? toInstant(row[9]) : null);                    // prunedAt
    }

    private static java.time.Instant toInstant(Object v) {
        if (v instanceof java.time.Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Instant");
    }
}
