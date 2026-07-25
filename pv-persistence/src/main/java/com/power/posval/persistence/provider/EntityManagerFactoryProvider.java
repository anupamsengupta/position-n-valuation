package com.power.posval.persistence.provider;

import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.HashMap;
import java.util.Map;

/**
 * Guice provider for JPA EntityManagerFactory.
 * Creates EMF from system properties, falling back to persistence.xml defaults.
 */
@Singleton
public class EntityManagerFactoryProvider implements Provider<EntityManagerFactory> {

    private volatile EntityManagerFactory emf;

    @Override
    public EntityManagerFactory get() {
        if (emf == null) {
            synchronized (this) {
                if (emf == null) {
                    Map<String, String> props = new HashMap<>();
                    String url = System.getProperty("pv.jdbc.url");
                    if (url != null) props.put("jakarta.persistence.jdbc.url", url);
                    String user = System.getProperty("pv.jdbc.user");
                    if (user != null) props.put("jakarta.persistence.jdbc.user", user);
                    String pass = System.getProperty("pv.jdbc.password");
                    if (pass != null) props.put("jakarta.persistence.jdbc.password", pass);
                    emf = Persistence.createEntityManagerFactory("pv-unit", props);
                }
            }
        }
        return emf;
    }
}
