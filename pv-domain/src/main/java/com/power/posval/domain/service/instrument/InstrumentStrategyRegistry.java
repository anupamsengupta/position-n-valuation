package com.power.posval.domain.service.instrument;

import com.power.posval.domain.port.service.InstrumentCalculationStrategy;

import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that maps instrument type strings to their {@link InstrumentCalculationStrategy}
 * implementations.
 *
 * <p>Strategies are discovered at Guice injector construction time via a multibinder
 * (S9b.5, S9b.6):
 * <pre>
 * Multibinder&lt;InstrumentCalculationStrategy&gt; binder =
 *     Multibinder.newSetBinder(binder(), InstrumentCalculationStrategy.class);
 * binder.addBinding().to(DaExchangeSpotStrategy.class);
 * // Future: binder.addBinding().to(IntraDayStrategy.class);
 * </pre>
 *
 * <p>Adding a new instrument type requires only an {@code addBinding()} call in the
 * relevant Guice module — no modifications to this registry or to any existing strategy
 * are needed (open/closed principle, S9b.10).
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #7 (Strategy Registry), S9b.5.
 */
public class InstrumentStrategyRegistry {

    private final Map<String, InstrumentCalculationStrategy> strategies;

    /**
     * Build the registry from the Guice-provided set of strategies.
     *
     * @param strategies all bound {@link InstrumentCalculationStrategy} implementations;
     *                   injected by Guice multibinder (S9b.6); must not be null or empty
     * @throws IllegalStateException if two strategies declare the same instrument type
     */
    @Inject
    public InstrumentStrategyRegistry(Set<InstrumentCalculationStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        this.strategies = strategies.stream()
            .collect(Collectors.toUnmodifiableMap(
                InstrumentCalculationStrategy::instrumentType,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                        "Duplicate InstrumentCalculationStrategy for instrument type '"
                        + a.instrumentType() + "': " + a.getClass().getName()
                        + " and " + b.getClass().getName());
                }
            ));
    }

    /**
     * Look up the strategy for a given instrument type.
     *
     * @param instrumentType the instrument type identifier (e.g. {@code "DA_EXCHANGE_SPOT"});
     *                       must not be null or blank
     * @return the strategy for this instrument type; never null
     * @throws IllegalArgumentException if no strategy is registered for the given type
     */
    public InstrumentCalculationStrategy forInstrument(String instrumentType) {
        Objects.requireNonNull(instrumentType, "instrumentType");
        InstrumentCalculationStrategy strategy = strategies.get(instrumentType);
        if (strategy == null) {
            throw new IllegalArgumentException(
                "No InstrumentCalculationStrategy registered for instrument type: '"
                + instrumentType + "'. Registered types: " + strategies.keySet());
        }
        return strategy;
    }

    /**
     * Return all registered instrument type identifiers.
     * Useful for validation and diagnostics.
     *
     * @return unmodifiable set of registered instrument type strings; never null
     */
    public Set<String> registeredTypes() {
        return strategies.keySet();
    }
}
