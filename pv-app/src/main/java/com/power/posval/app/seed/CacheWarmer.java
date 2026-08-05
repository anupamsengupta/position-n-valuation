package com.power.posval.app.seed;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.VolumeInterval;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.cache.CachedInterval;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Warms Redis caches at startup, AFTER DataSeeder has populated the database.
 * Processes monthly chunks in parallel using a configurable thread pool.
 *
 * <p>Market data warming drives calls through {@link MarketDataPort} (which
 * triggers {@link com.power.posval.domain.service.CachingMarketDataPort}'s
 * read-through: DB query → Redis put).
 *
 * <p>Volume cache warming queries the DB directly and calls
 * {@link VolumeCache#putAll} (VolumeCache is write-aside, not read-through).
 */
@Component
@Order(2) // DataSeeder is @Order(0) by default
public class CacheWarmer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);
    private static final String TENANT_ID = "default";

    private static final String[] FIXING_SERIES = {"EPEX_DA15", "NORDPOOL_SYS"};
    private static final String FORWARD_CURVE_SERIES = "EEX_BASE_DE";
    private static final String FX_PAIR = "EUR/NOK";
    private static final String INDEX_SERIES = "HICP-DE";
    private static final String[] VOLUME_SERIES_KEYS = {
            VolumeSeriesSeeder.WIND_SERIES_KEY.value(),
            VolumeSeriesSeeder.SOLAR_SERIES_KEY.value()
    };

    private static final int INTERVAL_MINUTES = 15;

    private final MarketDataPort marketDataPort;
    private final VolumeSeriesRepository volumeSeriesRepo;
    private final VolumeCache volumeCache;
    private final TransactionalExecutor txExecutor;
    private final TenantContext tenantContext;
    private final boolean warmupEnabled;
    private final int lookbackDays;
    private final int poolSize;

    public CacheWarmer(MarketDataPort marketDataPort,
                        VolumeSeriesRepository volumeSeriesRepo,
                        VolumeCache volumeCache,
                        TransactionalExecutor txExecutor,
                        TenantContext tenantContext,
                        @Value("${pv.cache.warmup.enabled:true}") boolean warmupEnabled,
                        @Value("${pv.cache.warmup.lookback-days:120}") int lookbackDays,
                        @Value("${pv.cache.warmup.pool-size:4}") int poolSize) {
        this.marketDataPort = marketDataPort;
        this.volumeSeriesRepo = volumeSeriesRepo;
        this.volumeCache = volumeCache;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
        this.warmupEnabled = warmupEnabled;
        this.lookbackDays = lookbackDays;
        this.poolSize = poolSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupEnabled) {
            log.info("Cache warm-up disabled");
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime rangeStart = now.minusDays(lookbackDays)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        // Forward to end of volume series coverage
        ZonedDateTime rangeEnd = ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

        List<YearMonth> months = buildMonthlyChunks(rangeStart, rangeEnd);
        log.info("Cache warm-up starting: {} months, pool-size={}, lookback={} days ({} → {})",
                months.size(), poolSize, lookbackDays,
                rangeStart.toLocalDate(), rangeEnd.toLocalDate());

        long startTime = System.currentTimeMillis();
        AtomicInteger totalEntries = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        List<Future<?>> futures = new ArrayList<>();
        for (YearMonth month : months) {
            futures.add(executor.submit(() -> {
                try {
                    int count = warmMonth(month, now);
                    totalEntries.addAndGet(count);
                    log.debug("Warmed month {}: {} entries", month, count);
                } catch (Exception e) {
                    log.warn("Cache warm-up failed for month {}: {}", month, e.getMessage());
                }
            }));
        }

        // Wait for all tasks
        for (Future<?> f : futures) {
            try {
                f.get(5, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Cache warm-up task failed: {}", e.getMessage());
            }
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Cache warm-up complete: {} months, {} entries in {}.{}s",
                months.size(), totalEntries.get(), elapsed / 1000, elapsed % 1000);
    }

    private int warmMonth(YearMonth month, ZonedDateTime now) {
        ZonedDateTime monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC);
        boolean isPast = monthEnd.isBefore(now);

        AtomicInteger count = new AtomicInteger();

        // Set tenant for CachingMarketDataPort
        tenantContext.setTenant(TENANT_ID);
        try {
            // Warm market data (read-through via CachingMarketDataPort)
            txExecutor.run(() -> {
                // Fixings for past months
                if (isPast) {
                    ZonedDateTime cursor = monthStart;
                    while (cursor.isBefore(monthEnd)) {
                        Instant intervalStart = cursor.toInstant();
                        for (String series : FIXING_SERIES) {
                            try {
                                marketDataPort.lookupFixing(series, intervalStart);
                                count.incrementAndGet();
                            } catch (Exception ignored) {}
                        }
                        cursor = cursor.plusMinutes(INTERVAL_MINUTES);
                    }
                }

                // Forward curves for future months
                if (!isPast) {
                    ZonedDateTime cursor = monthStart;
                    while (cursor.isBefore(monthEnd)) {
                        try {
                            marketDataPort.lookupForwardCurve(FORWARD_CURVE_SERIES,
                                    month, cursor.toInstant());
                            count.incrementAndGet();
                        } catch (Exception ignored) {}
                        cursor = cursor.plusMinutes(INTERVAL_MINUTES);
                    }
                }

                // FX rates (daily)
                ZonedDateTime dayCursor = monthStart;
                while (dayCursor.isBefore(monthEnd)) {
                    try {
                        marketDataPort.lookupFxRate(FX_PAIR, dayCursor.toInstant());
                        count.incrementAndGet();
                    } catch (Exception ignored) {}
                    dayCursor = dayCursor.plusDays(1);
                }

                // Index (monthly)
                try {
                    marketDataPort.lookupIndex(INDEX_SERIES, month.toString(), null);
                    count.incrementAndGet();
                } catch (Exception ignored) {}
            });

            // Warm volume cache (write-aside — query DB, push to Redis)
            txExecutor.run(() -> {
                Instant mStart = monthStart.toInstant();
                Instant mEnd = monthEnd.toInstant();
                for (String seriesKey : VOLUME_SERIES_KEYS) {
                    try {
                        Optional<VolumeSeries> seriesOpt = volumeSeriesRepo
                                .findCurrentBySeriesKeyAndRange(TENANT_ID, seriesKey, mStart, mEnd);
                        if (seriesOpt.isPresent()) {
                            Map<Instant, CachedInterval> entries = new HashMap<>();
                            for (VolumeInterval vi : seriesOpt.get().intervals()) {
                                entries.put(vi.intervalStart(), new CachedInterval(
                                        vi.intervalStart(), vi.intervalEnd(),
                                        vi.volume(), vi.energy(),
                                        false, "1", "v1"));
                            }
                            volumeCache.putAll(TENANT_ID, seriesKey, entries);
                            count.addAndGet(entries.size());
                        }
                    } catch (Exception e) {
                        log.debug("Volume cache warm-up skipped for {} month {}: {}",
                                seriesKey, month, e.getMessage());
                    }
                }
            });
        } finally {
            tenantContext.clear();
        }

        return count.get();
    }

    private List<YearMonth> buildMonthlyChunks(ZonedDateTime start, ZonedDateTime end) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = YearMonth.from(start.toLocalDate());
        YearMonth last = YearMonth.from(end.toLocalDate().minusDays(1));
        while (!cursor.isAfter(last)) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        return months;
    }
}
