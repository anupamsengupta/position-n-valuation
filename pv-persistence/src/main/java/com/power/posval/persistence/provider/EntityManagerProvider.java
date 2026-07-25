package com.power.posval.persistence.provider;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Guice provider for per-request EntityManager instances.
 * Creates a new EntityManager from the factory on each get().
 */
public class EntityManagerProvider implements Provider<EntityManager> {

    private final Provider<EntityManagerFactory> emfProvider;

    @Inject
    public EntityManagerProvider(Provider<EntityManagerFactory> emfProvider) {
        this.emfProvider = emfProvider;
    }

    @Override
    public EntityManager get() {
        return emfProvider.get().createEntityManager();
    }
}
