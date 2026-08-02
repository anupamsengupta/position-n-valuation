package com.power.posval.app.provider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import java.util.function.Supplier;

public class TransactionalExecutor {

    private final EntityManagerFactory emf;
    private final SpringEntityManagerProvider emProvider;

    public TransactionalExecutor(EntityManagerFactory emf, SpringEntityManagerProvider emProvider) {
        this.emf = emf;
        this.emProvider = emProvider;
    }

    public <T> T execute(Supplier<T> work) {
        EntityManager em = emf.createEntityManager();
        emProvider.bind(em);
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.get();
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            emProvider.unbind();
            if (em.isOpen()) {
                em.close();
            }
        }
    }

    public void run(Runnable work) {
        execute(() -> { work.run(); return null; });
    }
}
