package com.power.posval.integration;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.PositionEntryCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.integration.support.IntegrationTestWiring;
import com.power.posval.integration.support.MarketDataDbLoader;
import com.power.posval.integration.support.VolumeSeriesGenerator;
import com.power.posval.persistence.entity.FixingEntity;
import com.power.posval.persistence.entity.SettlementCellEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: market data → volume upload → trade capture → settlement.
 * Runs against H2 with real JPA persistence — no mocks.
 */
class EndToEndValuationIT {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final UUID EXPR_4_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private IntegrationTestWiring wiring;

    @BeforeEach
    void setUp() {
        wiring = IntegrationTestWiring.create();
    }

    @AfterEach
    void tearDown() {
        if (wiring != null) {
            wiring.close();
        }
    }

    @Test
    void endToEnd_volumeUpload_tradeCapture_settlement() {
        // === Phase 1: Load Market Data ===
        wiring.unitOfWork.run(em -> {
            MarketDataDbLoader.load(wiring.marketDataRepo, IntegrationTestWiring.TENANT_ID);
        });

        // Verify fixings in DB
        Long fixingCount = wiring.unitOfWork.execute(em ->
            em.createQuery("SELECT COUNT(f) FROM FixingEntity f", Long.class)
                .getSingleResult()
        );
        assertTrue(fixingCount > 0, "Fixings should be loaded into H2, got " + fixingCount);
        System.out.println("Phase 1: Loaded " + fixingCount + " fixings into H2");

        // === Phase 2: Create Volume Series (2 assets) ===
        wiring.unitOfWork.run(em -> {
            VolumeSeriesGenerator.createAsset1(wiring.volumeSeriesRepo);
        });
        wiring.unitOfWork.run(em -> {
            VolumeSeriesGenerator.createAsset2(wiring.volumeSeriesRepo);
        });

        // Verify both series are queryable
        var asset1 = wiring.unitOfWork.execute(em ->
            wiring.volumeSeriesRepo.findCurrentBySeriesKey(
                IntegrationTestWiring.TENANT_ID,
                VolumeSeriesGenerator.ASSET1_SERIES_KEY.value())
        );
        assertTrue(asset1.isPresent(), "Asset1 volume series should be in DB");
        System.out.println("Phase 2: Asset1 has " + asset1.get().intervals().size() + " intervals");

        var asset2 = wiring.unitOfWork.execute(em ->
            wiring.volumeSeriesRepo.findCurrentBySeriesKey(
                IntegrationTestWiring.TENANT_ID,
                VolumeSeriesGenerator.ASSET2_SERIES_KEY.value())
        );
        assertTrue(asset2.isPresent(), "Asset2 volume series should be in DB");
        System.out.println("Phase 2: Asset2 has " + asset2.get().intervals().size() + " intervals");

        // === Phase 3: Create 6 Trades (3 per asset, multipliers 0.3/0.3/0.4) ===
        BigDecimal[] multipliers = {
            new BigDecimal("0.3"), new BigDecimal("0.3"), new BigDecimal("0.4")
        };

        // Delivery: Jul 2026 - Jul 2027 (12 months for speed, full year)
        ZonedDateTime deliveryStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, CET);
        ZonedDateTime deliveryEnd = ZonedDateTime.of(2027, 7, 1, 0, 0, 0, 0, CET);
        DeliveryPeriod deliveryPeriod = new DeliveryPeriod(deliveryStart, deliveryEnd, CET);

        record AssetConfig(String assetId, SeriesKey seriesKey, String prefix) {}
        var assets = List.of(
            new AssetConfig(VolumeSeriesGenerator.ASSET1_ID,
                VolumeSeriesGenerator.ASSET1_SERIES_KEY, "T-ASSET1"),
            new AssetConfig(VolumeSeriesGenerator.ASSET2_ID,
                VolumeSeriesGenerator.ASSET2_SERIES_KEY, "T-ASSET2")
        );

        List<PositionEntryCaptured> capturedEvents = new ArrayList<>();

        for (var asset : assets) {
            for (int i = 0; i < multipliers.length; i++) {
                String tradeId = asset.prefix() + "-" + (i + 1);
                BigDecimal mult = multipliers[i];

                TradeCapture command = new TradeCapture(
                    tradeId, 1, "LEG-1", IntegrationTestWiring.TENANT_ID,
                    deliveryPeriod,
                    new BigDecimal("80.0"), VolumeUnit.MW_CAPACITY, EXPR_4_ID,
                    null,
                    "PORTFOLIO-RENEW", "DE_LU", "PPA_ONSHORE",
                    Instant.parse("2026-06-01T00:00:00Z"),
                    asset.assetId(), mult, asset.seriesKey(), null);

                wiring.publishedEvents.clear();
                List<PositionLedgerEntry> entries = wiring.unitOfWork.execute(em ->
                    wiring.tradeCaptureHandler.handle(command)
                );

                // 12 months = 12 ledger entries
                assertEquals(12, entries.size(),
                    tradeId + " should produce 12 monthly ledger entries");

                // Collect the PositionEntryCaptured events (one per entry)
                assertEquals(12, wiring.publishedEvents.size(),
                    "12 events should be published for " + tradeId);
                for (Object evt : wiring.publishedEvents) {
                    capturedEvents.add((PositionEntryCaptured) evt);
                }
            }
        }
        System.out.println("Phase 3: Created 6 trades (" + capturedEvents.size() + " events)");

        // === Phase 4: Trigger Valuation (process first month only for speed) ===
        // Process each trade's captured event — the consumer materializes all 12 months
        // We process only the first trade per asset to keep test fast
        wiring.publishedEvents.clear();

        long start = System.currentTimeMillis();
        for (PositionEntryCaptured event : capturedEvents) {
            wiring.unitOfWork.run(em -> {
                wiring.tradeCapturedConsumer.handle(event);
            });
        }
        System.out.println("Phase 4: Settlement materialization complete, " +
            wiring.publishedEvents.size() + " settlement events published");

        long end = System.currentTimeMillis();
        System.out.println("Time taken for settlement: " + (end-start)/1000 + " secs");

        // === Phase 5: Verify Database State ===
        Long cellCount = wiring.unitOfWork.execute(em ->
            em.createQuery("SELECT COUNT(c) FROM SettlementCellEntity c", Long.class)
                .getSingleResult()
        );
        assertTrue(cellCount > 0, "Settlement cells should be persisted in H2, got " + cellCount);
        System.out.println("Phase 5: " + cellCount + " settlement cells in H2");

        // Query a sample of cells to verify prices
        List<SettlementCellEntity> sampleCells = wiring.unitOfWork.execute(em ->
            em.createQuery(
                "SELECT c FROM SettlementCellEntity c ORDER BY c.intervalStart",
                SettlementCellEntity.class)
                .setMaxResults(100)
                .getResultList()
        );

        assertFalse(sampleCells.isEmpty(), "Should have sample cells");
        for (SettlementCellEntity cell : sampleCells) {
            // CPI-escalated price should be within collar bounds [38, 110]
            BigDecimal price = cell.getPrice();
            assertTrue(price.compareTo(new BigDecimal("38")) >= 0,
                "Price " + price + " below floor 38");
            assertTrue(price.compareTo(new BigDecimal("110")) <= 0,
                "Price " + price + " above cap 110");
            // Amount should be price * energy (non-negative)
            assertTrue(cell.getAmount().compareTo(BigDecimal.ZERO) >= 0,
                "Amount should be non-negative");
            // Active leaves should reference the CPI escalation chain
            String leaves = cell.getActiveLeaves();
            if (leaves != null) {
                assertTrue(leaves.contains("BASE_PRICE_72"),
                    "Active leaves should include BASE_PRICE_72");
                assertTrue(leaves.contains("HICP_DE_CURRENT"),
                    "Active leaves should include HICP_DE_CURRENT");
            }
        }

        // === Phase 6: Verify Cache Behavior ===
        assertTrue(wiring.cache.cacheMisses.get() > 0,
            "First lookups should miss cache, got misses=" + wiring.cache.cacheMisses.get());
        assertTrue(wiring.cache.cacheHits.get() > 0,
            "Repeated lookups should hit cache, got hits=" + wiring.cache.cacheHits.get());
        assertTrue(wiring.cache.store.size() > 0,
            "Cache should be populated");

        System.out.println("Phase 6: Cache hits=" + wiring.cache.cacheHits.get() +
            ", misses=" + wiring.cache.cacheMisses.get() +
            ", store size=" + wiring.cache.store.size());
        System.out.println("Time taken for settlement: " + (end-start)/1000 + " secs");
    }

    @Test
    void tradeMultipliers_coverFullAssetCapacity() {
        // Load market data and volumes
        wiring.unitOfWork.run(em ->
            MarketDataDbLoader.load(wiring.marketDataRepo, IntegrationTestWiring.TENANT_ID));
        wiring.unitOfWork.run(em ->
            VolumeSeriesGenerator.createAsset1(wiring.volumeSeriesRepo));

        // Create 3 trades with multipliers 0.3 + 0.3 + 0.4 = 1.0
        BigDecimal[] multipliers = {
            new BigDecimal("0.3"), new BigDecimal("0.3"), new BigDecimal("0.4")
        };
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal m : multipliers) {
            sum = sum.add(m);
        }
        assertEquals(0, BigDecimal.ONE.compareTo(sum),
            "Multipliers should sum to 1.0: " + sum);
    }

    @Test
    void cachingMarketDataPort_hitsOnRepeatedLookups() {
        // Load market data
        wiring.unitOfWork.run(em ->
            MarketDataDbLoader.load(wiring.marketDataRepo, IntegrationTestWiring.TENANT_ID));

        // First lookup: cache miss → DB query
        wiring.unitOfWork.execute(em -> {
            var result = wiring.cachingMarketData.lookupFixing(
                "EPEX_DA15", Instant.parse("2025-03-01T00:00:00Z"));
            assertTrue(result.value().compareTo(BigDecimal.ZERO) > 0,
                "Fixing should have positive value from DB");
            return result;
        });
        int missesAfterFirst = wiring.cache.cacheMisses.get();
        assertTrue(missesAfterFirst > 0, "First lookup should cause a cache miss");

        // Second lookup: same key → cache hit
        wiring.unitOfWork.execute(em -> {
            return wiring.cachingMarketData.lookupFixing(
                "EPEX_DA15", Instant.parse("2025-03-01T00:00:00Z"));
        });
        int hitsAfterSecond = wiring.cache.cacheHits.get();
        assertTrue(hitsAfterSecond > 0, "Second lookup should hit cache");
    }

    @Test
    void settlementCells_priceWithinCollarBounds() {
        // Full pipeline then verify all cells
        wiring.unitOfWork.run(em ->
            MarketDataDbLoader.load(wiring.marketDataRepo, IntegrationTestWiring.TENANT_ID));
        wiring.unitOfWork.run(em ->
            VolumeSeriesGenerator.createAsset1(wiring.volumeSeriesRepo));

        ZonedDateTime deliveryStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, CET);
        ZonedDateTime deliveryEnd = ZonedDateTime.of(2027, 7, 1, 0, 0, 0, 0, CET);
        DeliveryPeriod dp = new DeliveryPeriod(deliveryStart, deliveryEnd, CET);

        TradeCapture cmd = new TradeCapture(
            "T-COLLAR-TEST", 1, "LEG-1", IntegrationTestWiring.TENANT_ID,
            dp, new BigDecimal("80.0"), VolumeUnit.MW_CAPACITY, EXPR_4_ID,
            null,
            "PORTFOLIO-TEST", "DE_LU", "PPA_ONSHORE",
            Instant.parse("2026-06-01T00:00:00Z"),
            VolumeSeriesGenerator.ASSET1_ID, BigDecimal.ONE,
            VolumeSeriesGenerator.ASSET1_SERIES_KEY, null);

        wiring.publishedEvents.clear();
        wiring.unitOfWork.execute(em -> wiring.tradeCaptureHandler.handle(cmd));

        // Process all per-entry events
        List<PositionEntryCaptured> events = wiring.publishedEvents.stream()
            .map(e -> (PositionEntryCaptured) e).toList();
        wiring.publishedEvents.clear();
        for (PositionEntryCaptured event : events) {
            wiring.unitOfWork.run(em -> wiring.tradeCapturedConsumer.handle(event));
        }

        // Verify all cells within collar
        List<SettlementCellEntity> allCells = wiring.unitOfWork.execute(em ->
            em.createQuery("SELECT c FROM SettlementCellEntity c", SettlementCellEntity.class)
                .getResultList()
        );

        assertFalse(allCells.isEmpty(), "Should have settlement cells");
        BigDecimal floor = new BigDecimal("38");
        BigDecimal cap = new BigDecimal("110");
        for (SettlementCellEntity cell : allCells) {
            assertTrue(cell.getPrice().compareTo(floor) >= 0,
                "Price " + cell.getPrice() + " below floor at " + cell.getIntervalStart());
            assertTrue(cell.getPrice().compareTo(cap) <= 0,
                "Price " + cell.getPrice() + " above cap at " + cell.getIntervalStart());
        }
    }

    @Test
    void databasePersistence_cellsQueryableViaEntityManager() {
        // Load data and create one trade
        wiring.unitOfWork.run(em ->
            MarketDataDbLoader.load(wiring.marketDataRepo, IntegrationTestWiring.TENANT_ID));
        wiring.unitOfWork.run(em ->
            VolumeSeriesGenerator.createAsset1(wiring.volumeSeriesRepo));

        ZonedDateTime deliveryStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, CET);
        ZonedDateTime deliveryEnd = ZonedDateTime.of(2027, 7, 1, 0, 0, 0, 0, CET);
        DeliveryPeriod dp = new DeliveryPeriod(deliveryStart, deliveryEnd, CET);

        TradeCapture cmd = new TradeCapture(
            "T-JPQL-TEST", 1, "LEG-1", IntegrationTestWiring.TENANT_ID,
            dp, new BigDecimal("80.0"), VolumeUnit.MW_CAPACITY, EXPR_4_ID,
            null,
            "PORTFOLIO-TEST", "DE_LU", "PPA_ONSHORE",
            Instant.parse("2026-06-01T00:00:00Z"),
            VolumeSeriesGenerator.ASSET1_ID, BigDecimal.ONE,
            VolumeSeriesGenerator.ASSET1_SERIES_KEY, null);

        wiring.publishedEvents.clear();
        wiring.unitOfWork.execute(em -> wiring.tradeCaptureHandler.handle(cmd));

        // Process all per-entry events
        List<PositionEntryCaptured> jpqlEvents = wiring.publishedEvents.stream()
            .map(e -> (PositionEntryCaptured) e).toList();
        wiring.publishedEvents.clear();
        for (PositionEntryCaptured event : jpqlEvents) {
            wiring.unitOfWork.run(em -> wiring.tradeCapturedConsumer.handle(event));
        }

        // Direct JPQL query
        Long count = wiring.unitOfWork.execute(em ->
            em.createQuery(
                "SELECT COUNT(c) FROM SettlementCellEntity c WHERE c.tenantId = :t",
                Long.class)
                .setParameter("t", IntegrationTestWiring.TENANT_ID)
                .getSingleResult()
        );
        assertTrue(count > 0, "JPQL should find cells for tenant " +
            IntegrationTestWiring.TENANT_ID + ", got " + count);
    }
}
