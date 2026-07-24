package com.power.posval.persistence.batch;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

/**
 * Explicit transaction boundary wrapper.
 * Preferred over guice-persist @Transactional for visibility and portability.
 * Pattern #24, §17.2.
 */
public class UnitOfWork {

    private final Provider<EntityManager> emProvider;

    @Inject
    public UnitOfWork(Provider<EntityManager> emProvider) {
        this.emProvider = emProvider;
    }

    /**
     * Execute work within a JPA transaction.
     * Commit on success; rollback on exception.
     */
    public <T> T execute(TransactionalWork<T> work) {
        EntityManager em = emProvider.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.execute(em);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /** Void variant for operations without a return value. */
    public void run(TransactionalAction action) {
        execute(em -> { action.execute(em); return null; });
    }

    @FunctionalInterface
    public interface TransactionalWork<T> {
        T execute(EntityManager em);
    }

    @FunctionalInterface
    public interface TransactionalAction {
        void execute(EntityManager em);
    }
}
