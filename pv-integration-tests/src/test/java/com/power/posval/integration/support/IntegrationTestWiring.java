package com.power.posval.integration.support;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.repository.VolumeSeriesSpec;
import com.power.posval.domain.port.tenant.TenantContext;
import com.power.posval.domain.service.CachingMarketDataPort;
import com.power.posval.domain.service.PriceExpressionBasedEvaluator;
import com.power.posval.domain.service.DefaultTradeCaptureHandler;
import com.power.posval.domain.service.ProfileResolver;
import com.power.posval.domain.service.SettlementMaterializationJob;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import com.power.posval.kafka.TradeCapturedConsumer;
import com.power.posval.persistence.adapter.JpaMarketDataRepository;
import com.power.posval.persistence.adapter.JpaPositionLedgerRepository;
import com.power.posval.persistence.adapter.JpaSettlementCellRepository;
import com.power.posval.persistence.adapter.JpaVolumeSeriesRepository;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.batch.UnitOfWork;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual component wiring for integration tests — replaces Guice modules.
 * Creates an H2-backed EntityManagerFactory and wires all real JPA adapters.
 */
public class IntegrationTestWiring {

    private final EntityManagerFactory emf;
    private final ThreadLocal<EntityManager> emThreadLocal = new ThreadLocal<>();

    // JPA adapters
    public final JpaMarketDataRepository marketDataRepo;
    public final JpaVolumeSeriesRepository volumeSeriesRepo;
    public final JpaPositionLedgerRepository ledgerRepo;
    public final JpaSettlementCellRepository cellRepo;
    public final BatchWriter batchWriter;
    public final UnitOfWork unitOfWork;

    // Domain services
    public final CachingMarketDataPort cachingMarketData;
    public final DefaultTradeCaptureHandler tradeCaptureHandler;
    public final TradeCapturedConsumer tradeCapturedConsumer;
    public final SettlementMaterializationJob settlementJob;

    // Cache with tracking
    public final InMemoryMarketDataCache cache;

    // Event capture
    public final List<Object> publishedEvents = new ArrayList<>();
    public final DomainEventPublisher eventPublisher = publishedEvents::add;

    // Tenant
    public final ThreadLocalTenantContext tenantContext;
    // JpaVolumeSeriesRepository.toEntity() hardcodes tenantId to "default"
    public static final String TENANT_ID = "default";

    private IntegrationTestWiring() {
        // 1. Create H2-backed EntityManagerFactory
        emf = Persistence.createEntityManagerFactory("pv-integration-test");

        // 2. Provider<EntityManager> that reuses the thread-local EM
        jakarta.inject.Provider<EntityManager> emProvider = () -> {
            EntityManager em = emThreadLocal.get();
            if (em == null || !em.isOpen()) {
                em = emf.createEntityManager();
                emThreadLocal.set(em);
            }
            return em;
        };

        // 3. Batch infrastructure
        batchWriter = new BatchWriter(emProvider);
        unitOfWork = new UnitOfWork(emProvider);

        // 4. JPA repositories (real)
        marketDataRepo = new JpaMarketDataRepository(emProvider);
        volumeSeriesRepo = new JpaVolumeSeriesRepository(emProvider, batchWriter);
        ledgerRepo = new JpaPositionLedgerRepository(emProvider, batchWriter);
        cellRepo = new JpaSettlementCellRepository(emProvider, batchWriter);

        // 5. Cache (in-memory with hit/miss tracking)
        cache = new InMemoryMarketDataCache();

        // 6. Tenant context
        tenantContext = new ThreadLocalTenantContext();
        tenantContext.setTenant(TENANT_ID);

        // 7. Domain services
        NumericPrecision np = new DefaultNumericPrecision();
        cachingMarketData = new CachingMarketDataPort(cache, marketDataRepo, tenantContext);
        var exprRepo = new JsonPriceExpressionRepository();
        var priceEvaluator = new PriceExpressionBasedEvaluator(np);

        // ProfileResolver passes ref.tradeId() as tenantId to findCurrentBySeriesKey,
        // but JpaVolumeSeriesRepository.toEntity() hardcodes tenantId to "default".
        // Wrap the repo to normalize tenantId for lookups.
        VolumeSeriesRepository tenantNormalizedRepo = new VolumeSeriesRepository() {
            @Override public void save(VolumeSeries s) { volumeSeriesRepo.save(s); }
            @Override public Optional<VolumeSeries> findById(UUID id) { return volumeSeriesRepo.findById(id); }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String sk) {
                return volumeSeriesRepo.findCurrentBySeriesKey(TENANT_ID, sk);
            }
            @Override public Optional<VolumeSeries> findCurrentBySeriesKeyAndRange(String tenantId, String sk,
                                                                                     java.time.Instant rangeStart, java.time.Instant rangeEnd) {
                return volumeSeriesRepo.findCurrentBySeriesKeyAndRange(TENANT_ID, sk, rangeStart, rangeEnd);
            }
            @Override public List<VolumeSeries> findByTenantId(String t) { return volumeSeriesRepo.findByTenantId(TENANT_ID); }
            @Override public List<VolumeSeries> findAll(String t, VolumeSeriesSpec s) { return volumeSeriesRepo.findAll(TENANT_ID, s); }
            @Override public boolean existsByTradeIdAndTradeVersion(String tid, int tv) {
                return volumeSeriesRepo.existsByTradeIdAndTradeVersion(tid, tv);
            }
            @Override public void supersede(VolumeSeries o, VolumeSeries n) { volumeSeriesRepo.supersede(o, n); }
        };

        var volumeResolver = new ProfileResolver(tenantNormalizedRepo, np);

        settlementJob = new SettlementMaterializationJob(
            volumeResolver, priceEvaluator, cachingMarketData, exprRepo,
            cellRepo, eventPublisher, np);

        tradeCaptureHandler = new DefaultTradeCaptureHandler(ledgerRepo, eventPublisher);
        tradeCapturedConsumer = new TradeCapturedConsumer(
            ledgerRepo, cellRepo, settlementJob);
    }

    public static IntegrationTestWiring create() {
        return new IntegrationTestWiring();
    }

    public EntityManager getEntityManager() {
        EntityManager em = emThreadLocal.get();
        if (em == null || !em.isOpen()) {
            em = emf.createEntityManager();
            emThreadLocal.set(em);
        }
        return em;
    }

    public void close() {
        EntityManager em = emThreadLocal.get();
        if (em != null && em.isOpen()) {
            em.close();
        }
        emThreadLocal.remove();
        tenantContext.clear();
        if (emf.isOpen()) {
            emf.close();
        }
    }

    /**
     * In-memory MarketDataCache with hit/miss counters for testing.
     */
    public static class InMemoryMarketDataCache implements MarketDataCache {

        public final ConcurrentHashMap<String, MarketDataLookup> store = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<String, VolSurfaceLookup> volStore = new ConcurrentHashMap<>();
        public final AtomicInteger cacheHits = new AtomicInteger();
        public final AtomicInteger cacheMisses = new AtomicInteger();

        private String key(String tenantId, MarketDataType type, String series, String lookupKey) {
            return tenantId + ":" + type + ":" + series + ":" + lookupKey;
        }

        private String volKey(String tenantId, String surfaceId, String lookupKey) {
            return tenantId + ":VOL:" + surfaceId + ":" + lookupKey;
        }

        @Override
        public Optional<MarketDataLookup> get(String tenantId, MarketDataType type,
                                                String series, String lookupKey) {
            MarketDataLookup v = store.get(key(tenantId, type, series, lookupKey));
            if (v != null) {
                cacheHits.incrementAndGet();
                return Optional.of(v);
            }
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public void put(String tenantId, MarketDataType type,
                        String series, String lookupKey, MarketDataLookup value) {
            store.put(key(tenantId, type, series, lookupKey), value);
        }

        @Override
        public Optional<VolSurfaceLookup> getVolSurface(String tenantId, String surfaceId,
                                                          String lookupKey) {
            VolSurfaceLookup v = volStore.get(volKey(tenantId, surfaceId, lookupKey));
            if (v != null) {
                cacheHits.incrementAndGet();
                return Optional.of(v);
            }
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public void putVolSurface(String tenantId, String surfaceId,
                                  String lookupKey, VolSurfaceLookup value) {
            volStore.put(volKey(tenantId, surfaceId, lookupKey), value);
        }

        @Override
        public void invalidate(String tenantId, MarketDataType type, String series) {
            String prefix = tenantId + ":" + type + ":" + series + ":";
            store.keySet().removeIf(k -> k.startsWith(prefix));
        }

        @Override
        public void invalidate(String tenantId, MarketDataType type, String series,
                               Instant rangeStart, Instant rangeEnd) {
            invalidate(tenantId, type, series);
        }
    }
}
