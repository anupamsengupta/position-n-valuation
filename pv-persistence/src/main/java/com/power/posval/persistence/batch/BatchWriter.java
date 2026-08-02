package com.power.posval.persistence.batch;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import java.util.List;

/**
 * Batch writer for efficient JPA bulk inserts.
 * Flushes every batchSize entities to prevent first-level cache bloat.
 * Pattern #20, TR-017.
 */
public class BatchWriter {

    static final int DEFAULT_BATCH_SIZE = 50;

    private final Provider<EntityManager> emProvider;
    private final int batchSize;

    @Inject
    public BatchWriter(Provider<EntityManager> emProvider,
                       @Named("pv.batch.size") int batchSize) {
        this.emProvider = emProvider;
        this.batchSize = batchSize;
    }

    public BatchWriter(Provider<EntityManager> emProvider) {
        this(emProvider, DEFAULT_BATCH_SIZE);
    }

    /**
     * Persist all entities with periodic flush/clear.
     * Prevents OOM on large batches (2,976+ intervals per chunk).
     */
    public <T> void writeAll(List<T> entities) {
        EntityManager em = emProvider.get();
        for (int i = 0; i < entities.size(); i++) {
            em.persist(entities.get(i));
            if ((i + 1) % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        if (!entities.isEmpty() && entities.size() % batchSize != 0) {
            em.flush();
            em.clear();
        }
    }
}
