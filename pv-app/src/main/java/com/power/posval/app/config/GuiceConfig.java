package com.power.posval.app.config;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.util.Modules;
import com.power.posval.app.provider.SpringEntityManagerProvider;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.domain.port.tenant.TenantContext;
import com.power.posval.guice.DomainModule;
import com.power.posval.guice.ObservabilityModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Bootstraps the Guice {@link Injector} within the Spring ApplicationContext.
 * D-13: Guice is the canonical wiring; Spring only bridges Guice-created instances
 * into the ApplicationContext for @RestController, @KafkaListener, and Actuator.
 *
 * <p>Module composition:
 * <ul>
 *   <li>{@link DomainModule} — domain service bindings (pv-guice, Spring-free)</li>
 *   <li>{@link ObservabilityModule} — metrics port (pv-guice, Spring-free)</li>
 *   <li>{@link ConfigModule} — bridges Spring infrastructure into Guice, handles
 *       strategy selection (pv-app, boundary layer)</li>
 * </ul>
 *
 * <p>Infrastructure modules (PersistenceModule, CacheModule, EventModule, KafkaModule)
 * from pv-guice are NOT installed. Instead, ConfigModule provides their bindings
 * using Spring-managed shared-state beans (EntityManager provider, Redis caches,
 * TenantContext) to avoid duplicate connection pools and ensure shared state.
 */
@Configuration
public class GuiceConfig {

    @Bean
    public Injector guiceInjector(Environment env,
                                  SpringEntityManagerProvider emProvider,
                                  VolumeCache volumeCache,
                                  MarketDataCache marketDataCache,
                                  TenantContext tenantContext) {

        String pricingStrategy = env.getProperty("pv.pricing.strategy", "expression");

        ConfigModule configModule = new ConfigModule(
                pricingStrategy, emProvider, volumeCache, marketDataCache, tenantContext);

        // DomainModule binds PriceEvaluator → PriceExpressionBasedEvaluator by default.
        // When pricingStrategy="rule-engine", ConfigModule overrides that binding.
        // Modules.override lets ConfigModule's bindings win over DomainModule's
        // where they conflict, while adding non-conflicting bindings (infrastructure).
        Module coreModules = Modules.combine(new DomainModule(), new ObservabilityModule());

        return Guice.createInjector(Stage.PRODUCTION,
                Modules.override(coreModules).with(configModule));
    }
}
