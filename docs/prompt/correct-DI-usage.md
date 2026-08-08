Refactor pv-app so that Spring hosts a Guice-wired core instead of running as
a parallel DI. Guice is the canonical wiring; Spring only bridges Guice-created
instances into the ApplicationContext for @RestController, @KafkaListener, and
Actuator to consume.

## Architectural principle (D-13)

- pv-domain, pv-persistence, pv-kafka, pv-redis, pv-guice must stay Spring-free.
- pv-app is the only Spring-aware module.
- Every domain service, port, handler, resolver, and job must be constructed
  by Guice. Spring @Bean methods must NOT call `new` on domain classes.
- Spring @Bean methods return `injector.getInstance(SomeType.class)` and
  nothing else, unless there is a documented reason to wrap.

## Phase 1 — Investigate first, DO NOT edit yet

1. Read pv-app/src/main/java/com/power/posval/app/config/DomainServiceConfig.java
   in full. List every @Bean it defines with its type.
2. Read pv-guice/**/DomainModule.java and any other Guice AbstractModule
   subclasses in pv-guice. List every binding present.
3. Diff the two lists. For each Spring @Bean, classify:
    - PRESENT in Guice with matching binding
    - MISSING from Guice (needs a new binding)
    - CONDITIONAL in Spring (e.g. @ConditionalOnProperty) — flag separately
    - WRAPPED in Spring (anonymous inner class, adapter) — flag separately
4. Find where the Guice Injector is (or isn't) created in pv-app. Search for
   `Guice.createInjector`, `Stage.PRODUCTION`, and any @Bean of type Injector.
5. Report the four lists and the Injector state. STOP here and wait for me
   to confirm before making any code change.

## Phase 2 — Add missing Guice bindings

For each MISSING binding found in Phase 1:
- Add it to the appropriate existing Guice module in pv-guice, or a new
  focused module if the concern is distinct (e.g. HandlersModule,
  QueryServicesModule, MaterializationModule).
- Use constructor injection with @Inject on the domain class where it isn't
  already present. Do NOT add Spring annotations to domain classes.
- Prefer @Provides methods over toInstance() for anything with dependencies.
- Preserve singleton semantics: bind with .in(Singleton.class) for services
  that are singletons in the current Spring config.
- For NumericPrecision, keep DefaultNumericPrecision as the singleton binding.
- For PriceEvaluator, model the two strategies (expression, rule-engine) as
  a Guice choice. Do NOT bake @ConditionalOnProperty into Guice. Instead,
  bind PriceEvaluator based on a config value read at Injector creation
  time (see Phase 3 for how the config crosses the Spring→Guice boundary).

Run `mvn -pl pv-guice test` after this phase. Fix compile or test failures
before continuing.

## Phase 3 — Bootstrap the Injector in pv-app

1. Create pv-app/src/main/java/com/power/posval/app/config/GuiceConfig.java
   that:
    - Reads pv.pricing.strategy and any other properties Guice needs from
      Spring's Environment.
    - Constructs the Guice Injector once, in Stage.PRODUCTION, passing
      Spring-sourced config into a ConfigModule (new, in pv-app, Spring-aware
      is fine here).
    - Exposes the Injector as a Spring @Bean so DomainServiceConfig can use it.
2. The ConfigModule lives in pv-app because it is the boundary layer.
   pv-guice must not import Spring.

## Phase 4 — Rewrite DomainServiceConfig

Replace every @Bean body with a single line delegating to the Injector.
Example shape (do not copy verbatim, apply to every bean in the file):

    @Bean
    public TradeCaptureHandler tradeCaptureHandler(Injector injector) {
        return injector.getInstance(TradeCaptureHandler.class);
    }

Rules:
- Remove all `new DefaultXxx(...)` calls from DomainServiceConfig.
- Remove @ConditionalOnProperty from priceEvaluator; the strategy choice
  now lives in the Guice module driven by the ConfigModule from Phase 3.
- Preserve any @Primary annotations.
- Keep bean names/method names identical so downstream @Autowired callers
  don't break.

## Phase 5 — The VolumeSeriesRepository wrapper: STOP and report

The current @Bean for VolumeSeriesRepository is an anonymous inner class that
hardcodes DEFAULT_TENANT ("default") on every call, discarding the tenantId
argument. This is a tenant isolation bug, not a wiring concern.

Do NOT silently replicate this behavior in Guice.
Do NOT silently fix it either.

Instead:
- Identify how tenant context is meant to propagate (search for TenantContext,
  ThreadLocal usage, @RequestScope beans, Kafka header extraction, and any
  interceptor or filter in pv-app).
- Report what you found.
- Propose two options: (a) bind VolumeSeriesRepository directly to
  JpaVolumeSeriesRepository and pass the real tenant through, (b) keep an
  explicit adapter that reads TenantContext.
- STOP and wait for my decision before wiring this bean.

## Phase 6 — Verify

1. `mvn clean install` at the root. All modules must build.
2. `mvn test` in pv-app and pv-integration-tests. Report any failures with
   root cause, do not paper over them.
3. Confirm no Spring imports leaked into pv-domain, pv-persistence,
   pv-kafka, pv-redis, or pv-guice:
   grep -rn "org.springframework" pv-domain pv-persistence pv-kafka pv-redis pv-guice
   Must return zero results.
4. Confirm no `new DefaultXxx` or `new SettlementRevaluationService` etc.
   remain in DomainServiceConfig:
   grep -n "new " pv-app/src/main/java/com/power/posval/app/config/DomainServiceConfig.java
   Only imports and injector.getInstance calls should remain.

## Reporting

At the end, produce a short summary:
- Bindings added to Guice, and in which module
- Files changed in pv-app
- Any behavior changes (there should be none intended)
- The tenant-wrapper decision I made in Phase 5 and how you implemented it
- Any tests that had to change and why

Do not commit. I will review the diff.