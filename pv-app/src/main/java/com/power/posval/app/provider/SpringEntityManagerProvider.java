package com.power.posval.app.provider;

import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

public class SpringEntityManagerProvider implements Provider<EntityManager> {

    private final EntityManagerFactory emf;
    private final ThreadLocal<EntityManager> bound = new ThreadLocal<>();

    public SpringEntityManagerProvider(EntityManagerFactory emf) {
        this.emf = emf;
    }

    public void bind(EntityManager em) {
        bound.set(em);
    }

    public void unbind() {
        bound.remove();
    }

    @Override
    public EntityManager get() {
        EntityManager em = bound.get();
        if (em != null && em.isOpen()) {
            return em;
        }
        return emf.createEntityManager();
    }
}
