package com.power.posval.guice;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.power.posval.kafka.OutboxRelayProducer;

/**
 * Application bootstrap. §19.1, TR-045.
 * Module composition order: Persistence before Domain (for Provider&lt;EM&gt;),
 * TenantModule before anything that uses @TenantAware.
 */
public class PositionValuationApp {

    private PositionValuationApp() {}

    /**
     * Create a fully wired Guice injector with all modules.
     * Used by application entry points and integration tests.
     */
    public static Injector createInjector() {
        return Guice.createInjector(
            new PersistenceModule(),
            new DomainModule(),
            new TenantModule(),
            new EventModule(),
            new CacheModule(),
            new KafkaModule(),
            new ObservabilityModule()
        );
    }

    public static void main(String[] args) {
        Injector injector = createInjector();

        // Start outbox relay poller (polls every 100ms for unpublished rows)
        OutboxRelayProducer relay = injector.getInstance(OutboxRelayProducer.class);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Graceful shutdown — close connections
        }));

        System.out.println("Position & Valuation system started.");
    }
}
