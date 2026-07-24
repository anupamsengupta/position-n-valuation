package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.telemetry.MetricsPort;

/**
 * Guice module for observability infrastructure. §18b.9.
 * Binds MetricsPort and health check aggregator.
 */
public class ObservabilityModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MetricsPort.class)
            .to(NoOpMetricsAdapter.class)
            .in(Singleton.class);
    }

    /** Default no-op implementation. Replace with MicrometerMetricsAdapter in production. */
    static class NoOpMetricsAdapter implements MetricsPort {
        @Override
        public void recordDuration(String metricName, java.time.Duration duration,
                                    String... tagKeyValuePairs) {}

        @Override
        public void increment(String metricName, String... tagKeyValuePairs) {}

        @Override
        public void recordValue(String metricName, double value,
                                 String... tagKeyValuePairs) {}
    }
}
