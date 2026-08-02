package com.power.posval.persistence.adapter;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import com.power.posval.persistence.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JpaMarketDataRepositoryTest {

    private static final String TENANT = "test-tenant";
    private static final Instant NOW = Instant.parse("2025-03-01T00:00:00Z");

    private List<Object> persisted;
    private JpaMarketDataRepository repo;

    @BeforeEach
    void setUp() {
        persisted = new ArrayList<>();
        // Dynamic proxy avoids implementing all EntityManager methods
        EntityManager em = (EntityManager) Proxy.newProxyInstance(
            EntityManager.class.getClassLoader(),
            new Class[]{EntityManager.class},
            (proxy, method, args) -> {
                if ("persist".equals(method.getName())) {
                    persisted.add(args[0]);
                    return null;
                }
                return null;
            });
        repo = new JpaMarketDataRepository(() -> em);
    }

    @Test
    void saveFixingPersistsEntity() {
        var lookup = new MarketDataLookup(
            new BigDecimal("42.50"), 5L, "EPEX_DA15", NOW, QualityState.VALIDATED);

        repo.saveFixing(TENANT, "EPEX_DA15", NOW, lookup);

        assertEquals(1, persisted.size());
        var entity = (FixingEntity) persisted.get(0);
        assertEquals(TENANT, entity.getTenantId());
        assertEquals("EPEX_DA15", entity.getSeries());
        assertEquals(NOW, entity.getIntervalStart());
        assertEquals(new BigDecimal("42.50"), entity.getValue());
        assertEquals(5L, entity.getVersionId());
        assertEquals("VALIDATED", entity.getQualityState());
    }

    @Test
    void saveForwardCurvePersistsEntity() {
        var lookup = new MarketDataLookup(
            new BigDecimal("74.20"), 3L, "EEX_BASE", NOW, QualityState.VALIDATED);

        repo.saveForwardCurve(TENANT, "EEX_BASE", YearMonth.of(2025, 3), NOW, lookup);

        assertEquals(1, persisted.size());
        var entity = (ForwardCurveEntity) persisted.get(0);
        assertEquals("2025-03", entity.getPillar());
    }

    @Test
    void saveFxRatePersistsEntity() {
        var lookup = new MarketDataLookup(
            new BigDecimal("1.0842"), 1L, "EUR/USD", NOW, QualityState.VALIDATED);

        repo.saveFxRate(TENANT, "EUR/USD", NOW, lookup);

        assertEquals(1, persisted.size());
        var entity = (FxRateEntity) persisted.get(0);
        assertEquals("EUR/USD", entity.getCurrencyPair());
        assertEquals(new BigDecimal("1.0842"), entity.getRate());
    }

    @Test
    void saveVolSurfacePersistsEntity() {
        var lookup = new VolSurfaceLookup(
            new BigDecimal("0.25"), 2L, "DE_POWER", 0.5, "3M",
            NOW, QualityState.VALIDATED);

        repo.saveVolSurface(TENANT, "DE_POWER", 0.5, "3M", NOW, lookup);

        assertEquals(1, persisted.size());
        var entity = (VolSurfaceEntity) persisted.get(0);
        assertEquals("DE_POWER", entity.getSurfaceId());
        assertEquals(0.5, entity.getStrikeDelta());
        assertEquals("3M", entity.getExpiryTenor());
        assertEquals(new BigDecimal("0.25"), entity.getImpliedVolatility());
    }

    @Test
    void saveSpreadPersistsEntity() {
        var lookup = new MarketDataLookup(
            new BigDecimal("1.50"), 4L, "SPREAD_1", NOW, QualityState.VALIDATED);

        repo.saveSpread(TENANT, "SPREAD_1", NOW, lookup);

        assertEquals(1, persisted.size());
        var entity = (SpreadEntity) persisted.get(0);
        assertEquals("SPREAD_1", entity.getSeries());
    }

    @Test
    void saveIndexPersistsEntity() {
        var lookup = new MarketDataLookup(
            new BigDecimal("100.0"), 1L, "CPI_DE", NOW, QualityState.VALIDATED);

        repo.saveIndex(TENANT, "CPI_DE", "M-1", lookup);

        assertEquals(1, persisted.size());
        var entity = (IndexValueEntity) persisted.get(0);
        assertEquals("CPI_DE", entity.getSeries());
        assertEquals("M-1", entity.getRefMonthExpression());
    }

    @Test
    void tenantIsolationInPersistence() {
        var lookup = new MarketDataLookup(
            BigDecimal.ONE, 1L, "S", NOW, QualityState.VALIDATED);

        repo.saveFixing("tenant-A", "S", NOW, lookup);
        repo.saveFixing("tenant-B", "S", NOW, lookup);

        assertEquals(2, persisted.size());
        assertEquals("tenant-A", ((FixingEntity) persisted.get(0)).getTenantId());
        assertEquals("tenant-B", ((FixingEntity) persisted.get(1)).getTenantId());
    }
}
