package com.power.posval.app.config;

import com.ctrm.ruleengine.api.RuleEngine;
import com.ctrm.ruleengine.core.DefaultRuleEngine;
import com.ctrm.ruleengine.core.cache.InMemoryRuleCache;
import com.ctrm.ruleengine.core.conflict.DefaultConflictResolver;
import com.ctrm.ruleengine.core.dao.json.JsonRuleDao;
import com.ctrm.ruleengine.core.dao.json.JsonRuleSetDao;
import com.ctrm.ruleengine.core.eval.DefaultDecisionEvaluator;
import com.ctrm.ruleengine.core.failure.DefaultFailureHandler;
import com.ctrm.ruleengine.core.resolve.CacheBackedRuleResolver;
import com.ctrm.ruleengine.model.Rule;
import com.ctrm.ruleengine.model.RuleSet;
import com.ctrm.ruleengine.mvel.MvelEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.power.posval.app.calendar.StubMarketCalendarAdapter;
import com.power.posval.app.pricing.PriceRuleDefinition;
import com.power.posval.app.pricing.RuleEngineBasedEvaluator;
import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.port.MarketCalendarPort;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.app.cache.CaffeineMarketDataCache;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.*;
import com.power.posval.domain.port.tenant.TenantContext;
import com.power.posval.domain.service.CachingMarketDataPort;
import com.power.posval.domain.service.PriceEvaluator;
import com.power.posval.domain.service.stub.JsonMeteredActualRepository;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import com.power.posval.persistence.adapter.*;
import com.power.posval.domain.port.repository.AuctionImportSessionRepository;
import com.power.posval.domain.port.repository.BalancingGroupRepository;
import com.power.posval.domain.port.repository.BlockDefinitionRepository;
import com.power.posval.domain.port.repository.ExchangeFeeScheduleRepository;
import com.power.posval.domain.port.repository.HolidayCalendarRepository;
import com.power.posval.domain.port.repository.ImbalanceRecordRepository;
import com.power.posval.domain.port.repository.NominationRepository;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.domain.port.repository.TARGET2CalendarRepository;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.batch.UnitOfWork;
import com.power.posval.persistence.event.OutboxDomainEventPublisher;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

import com.power.posval.domain.model.value.AuctionFolderPollerConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guice module that bridges Spring-managed infrastructure into the Guice object graph.
 * Lives in pv-app because it is the boundary layer (D-13: pv-guice must not import Spring).
 *
 * <p>Receives shared-state beans from Spring (EntityManager provider, caches, TenantContext)
 * and lets Guice create stateless services (repos, publishers, batch writers) via class binding.
 *
 * <p>Handles PriceEvaluator strategy selection: when "rule-engine" is configured,
 * overrides DomainModule's default PriceExpressionBasedEvaluator binding.
 */
public class ConfigModule extends AbstractModule {

    private final String pricingStrategy;
    private final Provider<EntityManager> emProvider;
    private final VolumeCache volumeCache;
    private final MarketDataCache marketDataCache;
    private final TenantContext tenantContext;
    private final long l1MaxSize;
    private final long l1TtlHours;

    public ConfigModule(String pricingStrategy,
                        Provider<EntityManager> emProvider,
                        VolumeCache volumeCache,
                        MarketDataCache marketDataCache,
                        TenantContext tenantContext,
                        long l1MaxSize,
                        long l1TtlHours) {
        this.pricingStrategy = pricingStrategy;
        this.emProvider = emProvider;
        this.volumeCache = volumeCache;
        this.marketDataCache = marketDataCache;
        this.tenantContext = tenantContext;
        this.l1MaxSize = l1MaxSize;
        this.l1TtlHours = l1TtlHours;
    }

    @Override
    protected void configure() {
        // --- Shared-state beans from Spring (must be same instance) ---
        bind(EntityManager.class).toProvider(emProvider);
        bind(VolumeCache.class).toInstance(volumeCache);
        // L1 Caffeine cache wraps the L2 cache (Redis/InMemory)
        MarketDataCache l1Cache = new CaffeineMarketDataCache(marketDataCache, l1MaxSize, l1TtlHours);
        bind(MarketDataCache.class).toInstance(l1Cache);
        bind(TenantContext.class).toInstance(tenantContext);

        // --- Batch infrastructure (stateless, Guice creates via @Inject) ---
        bindConstant().annotatedWith(Names.named("pv.batch.size")).to(50);
        bind(BatchWriter.class).in(Singleton.class);
        bind(UnitOfWork.class).in(Singleton.class);

        // --- Repository adapters (stateless, Guice creates via @Inject) ---
        bind(PositionLedgerRepository.class).to(JpaPositionLedgerRepository.class).in(Singleton.class);
        bind(VolumeSeriesRepository.class).to(JpaVolumeSeriesRepository.class).in(Singleton.class);
        bind(SettlementCellRepository.class).to(JpaSettlementCellRepository.class).in(Singleton.class);
        bind(RollupRepository.class).to(JpaRollupRepository.class).in(Singleton.class);
        bind(TradeLegRollupRepository.class).to(JpaTradeLegRollupRepository.class).in(Singleton.class);
        bind(DependencyIndex.class).to(JpaDependencyIndex.class).in(Singleton.class);
        bind(MarketDataRepository.class).to(JpaMarketDataRepository.class).in(Singleton.class);
        bind(TradeIntervalCache.class).to(JpaTradeIntervalCache.class).in(Singleton.class);

        // --- Event publishing ---
        bind(DomainEventPublisher.class).to(OutboxDomainEventPublisher.class).in(Singleton.class);

        // --- Market data port (cache-through decorator) ---
        bind(MarketDataPort.class).to(CachingMarketDataPort.class).in(Singleton.class);

        // --- DA Exchange Spot repository adapters (S9.2) ---
        // These mirror PersistenceModule's DA bindings; ConfigModule is the active
        // binding source in pv-app because GuiceConfig does not install PersistenceModule.
        bind(AuctionImportSessionRepository.class)
            .to(JpaAuctionImportSessionRepository.class).in(Singleton.class);
        bind(NominationRepository.class)
            .to(JpaNominationRepository.class).in(Singleton.class);
        bind(ImbalanceRecordRepository.class)
            .to(JpaImbalanceRecordRepository.class).in(Singleton.class);
        bind(OperationalAlertRepository.class)
            .to(JpaOperationalAlertRepository.class).in(Singleton.class);
        bind(ExchangeFeeScheduleRepository.class)
            .to(JpaExchangeFeeScheduleRepository.class).in(Singleton.class);
        bind(HolidayCalendarRepository.class)
            .to(JpaHolidayCalendarRepository.class).in(Singleton.class);
        bind(BlockDefinitionRepository.class)
            .to(JpaBlockDefinitionRepository.class).in(Singleton.class);
        bind(BalancingGroupRepository.class)
            .to(JpaBalancingGroupRepository.class).in(Singleton.class);
        bind(TARGET2CalendarRepository.class)
            .to(JpaTargetCalendarRepository.class).in(Singleton.class);

        // --- MarketCalendarPort: simulator stub (D-14, A-1, S9.3) ---
        // Production host must bind a real adapter with HolidayCalendar support.
        bind(MarketCalendarPort.class)
            .to(StubMarketCalendarAdapter.class).in(Singleton.class);

        // --- Stub services (swap for real adapters when available) ---
        bind(PriceExpressionRepository.class).to(JsonPriceExpressionRepository.class).in(Singleton.class);
        bind(MeteredActualRepository.class).to(JsonMeteredActualRepository.class).in(Singleton.class);
        // ForwardMarkService binding: DomainModule binds DefaultForwardMarkService (ADR-002).
        // No override needed here — the stub was replaced when ADR-002 was delivered.

        // --- PriceEvaluator strategy override ---
        if ("rule-engine".equals(pricingStrategy)) {
            // Override DomainModule's default PriceExpressionBasedEvaluator binding.
            // Construction mirrors RuleEngineConfig but without Spring dependency.
            bind(PriceEvaluator.class)
                .toProvider(RuleEngineEvaluatorProvider.class)
                .in(Singleton.class);
        }
        // When "expression" (default), DomainModule's binding stands — no override needed.
    }

    /**
     * Provides {@link AuctionFolderPollerConfig} with simulator-scope defaults (D-14).
     *
     * <p>Directories default to {@code ./da-import/{inbox,processed,failed}} relative to the
     * working directory. The {@code defaultTenantId} is {@code "default"} (simulator
     * single-tenant, D-14). Production hosts must supply a real
     * {@code AuctionFolderPollerConfig} derived from their configuration source (SSM, etc.)
     * and must derive the tenant from the filename convention or out-of-band config.
     *
     * <p>The poller is only started when {@code pv.da.poller.enabled=true} is set in
     * application.yml (see {@code DaPollerConfig} in pv-app). S9.4, S6.3.
     */
    @Provides
    @Singleton
    AuctionFolderPollerConfig auctionFolderPollerConfig() {
        Path daImportRoot = Path.of("da-import");
        return new AuctionFolderPollerConfig(
            daImportRoot.resolve("inbox"),
            daImportRoot.resolve("processed"),
            daImportRoot.resolve("failed"),
            AuctionFolderPollerConfig.DEFAULT_POLL_INTERVAL,
            AuctionFolderPollerConfig.DEFAULT_FILE_PATTERN,
            "default"   // simulator single-tenant (D-14); production host overrides this
        );
    }

    /**
     * Guice provider for the rule-engine-based PriceEvaluator.
     * Loads rules and definitions from classpath JSON resources at construction time.
     */
    static class RuleEngineEvaluatorProvider implements jakarta.inject.Provider<PriceEvaluator> {

        private final NumericPrecision np;
        private final PriceExpressionRepository priceExpressionRepo;

        @jakarta.inject.Inject
        RuleEngineEvaluatorProvider(NumericPrecision np,
                                    PriceExpressionRepository priceExpressionRepo) {
            this.np = np;
            this.priceExpressionRepo = priceExpressionRepo;
        }

        @Override
        public PriceEvaluator get() {
            try {
                RuleEngine ruleEngine = buildRuleEngine();
                List<PriceRuleDefinition> definitions = loadDefinitions();

                Map<PriceExpression, String> expressionToId = new HashMap<>();
                for (PriceRuleDefinition def : definitions) {
                    UUID id = UUID.fromString(def.priceExpressionId());
                    priceExpressionRepo.findById(id).ifPresent(
                        expr -> expressionToId.put(expr, def.priceExpressionId()));
                }

                Map<String, PriceRuleDefinition> definitionsByExprId = new HashMap<>();
                for (PriceRuleDefinition def : definitions) {
                    definitionsByExprId.put(def.priceExpressionId(), def);
                }

                return new RuleEngineBasedEvaluator(ruleEngine, expressionToId, definitionsByExprId, np);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to initialize rule engine PriceEvaluator", e);
            }
        }

        private RuleEngine buildRuleEngine() throws IOException {
            Path rulesPath = extractToTempFile("stub/price-rules.json");
            JsonRuleDao ruleDao = new JsonRuleDao(rulesPath);
            InMemoryRuleCache cache = new InMemoryRuleCache();
            for (Rule rule : ruleDao.findAll()) {
                cache.put(rule);
            }
            cache.markWarm();

            Path rulesetsPath = extractToTempFile("stub/price-rulesets.json");
            JsonRuleSetDao ruleSetDao = new JsonRuleSetDao(rulesetsPath);
            CacheBackedRuleResolver resolver = new CacheBackedRuleResolver(cache);
            for (RuleSet rs : ruleSetDao.findAll()) {
                resolver.index(rs);
            }

            var decisionEvaluator = new DefaultDecisionEvaluator(
                new DefaultConflictResolver(), new DefaultFailureHandler());
            return new DefaultRuleEngine(resolver, decisionEvaluator, List.of(new MvelEvaluator()));
        }

        private List<PriceRuleDefinition> loadDefinitions() throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            try (var in = getClass().getClassLoader().getResourceAsStream("stub/price-rule-definitions.json")) {
                if (in == null) throw new IOException("stub/price-rule-definitions.json not found on classpath");
                return mapper.readValue(in, new TypeReference<>() {});
            }
        }

        private Path extractToTempFile(String classpathLocation) throws IOException {
            try (var in = getClass().getClassLoader().getResourceAsStream(classpathLocation)) {
                if (in == null) throw new IOException(classpathLocation + " not found on classpath");
                Path tempFile = Files.createTempFile("rule-engine-", ".json");
                tempFile.toFile().deleteOnExit();
                try (var out = Files.newOutputStream(tempFile)) {
                    in.transferTo(out);
                }
                return tempFile;
            }
        }
    }
}
