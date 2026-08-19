package com.power.posval.app.seed;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.repository.MarketDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Random;

/**
 * Generates 15-minute granularity market data for the pv-app seeder:
 *
 * <ul>
 *   <li><b>Fixings (settled)</b>: EPEX_DA15 from 2026-07-01 → now (past data, ex-post prices)</li>
 *   <li><b>Forward curves</b>: EEX_BASE_DE from now → 2028-07-01 (future data, monthly pillars with 15-min values)</li>
 *   <li><b>NORDPOOL fixings</b>: NORDPOOL_SYS from 2026-07-01 → now (for EXPR-5 cross-border PPA)</li>
 *   <li><b>FX rates</b>: EUR/NOK daily from 2026-07-01 → 2028-07-01</li>
 *   <li><b>Indices</b>: HICP-DE monthly from 2023-11 → 2028-06</li>
 * </ul>
 *
 * Prices follow a mean-reverting random walk centred on current EU baseload (~85 EUR/MWh):
 * - Inter-interval jitter: 1–2.5 EUR
 * - Overall range: 80–90 EUR/MWh (base ± 5)
 * - Direction biased toward base to prevent boundary sticking
 * - NORDPOOL: ~950 NOK/MWh ± 55, jitter 10–28 NOK
 *
 * Consistent with stub/market-data.json so that JSON-backed tests and
 * DB-seeded runtime produce comparable price levels.
 */
public final class MarketDataSeriesSeeder {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSeriesSeeder.class);
    private static final String TENANT_ID = "default";
    private static final int INTERVAL_MINUTES = 15;

    private MarketDataSeriesSeeder() {}

    /**
     * Seed all 15-min market data. Returns [fixings, forwardCurves, fxRates, indices].
     */
    public static int[] seed(MarketDataRepository repo) {
        prevPrice.clear();
        ZonedDateTime seriesStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime seriesEnd = ZonedDateTime.of(2028, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        // Truncate "now" to the last 15-min boundary
        ZonedDateTime fixingEnd = now.withMinute((now.getMinute() / 15) * 15).withSecond(0).withNano(0);

        int fixings = seedFixings(repo, "EPEX_DA15", seriesStart, fixingEnd, 42);
        log.info("Seeded {} EPEX_DA15 fixings (15-min, {} → {})", fixings, seriesStart.toLocalDate(), fixingEnd);

        int nordpool = seedFixings(repo, "NORDPOOL_SYS", seriesStart, fixingEnd, 77);
        log.info("Seeded {} NORDPOOL_SYS fixings", nordpool);

        int curves = seedForwardCurves(repo, "EEX_BASE_DE", fixingEnd, seriesEnd, 123);
        log.info("Seeded {} EEX_BASE_DE forward curve points (15-min, {} → {})", curves, fixingEnd.toLocalDate(), seriesEnd.toLocalDate());

        // EPEX_DA15 forward curves — same series referenced by EXPR-002/003/004
        // MarketDataLeaf nodes for forward mark valuation (marketPriceExpressionId)
        int epexCurves = seedForwardCurves(repo, "EPEX_DA15", fixingEnd, seriesEnd, 456);
        log.info("Seeded {} EPEX_DA15 forward curve points (15-min, {} → {})", epexCurves, fixingEnd.toLocalDate(), seriesEnd.toLocalDate());

        // NORDPOOL_SYS forward curves — referenced by EXPR-005 cross-border PPA
        int nordpoolCurves = seedForwardCurves(repo, "NORDPOOL_SYS", fixingEnd, seriesEnd, 789);
        log.info("Seeded {} NORDPOOL_SYS forward curve points (15-min, {} → {})", nordpoolCurves, fixingEnd.toLocalDate(), seriesEnd.toLocalDate());

        int fxRates = seedFxRates(repo, seriesStart, seriesEnd);
        log.info("Seeded {} EUR/NOK FX rates", fxRates);

        int indices = seedIndices(repo);
        log.info("Seeded {} HICP-DE index values", indices);

        return new int[]{fixings + nordpool, curves + epexCurves + nordpoolCurves, fxRates, indices};
    }

    /**
     * Seed 15-min fixings (ex-post settled prices) for a given series.
     */
    private static int seedFixings(MarketDataRepository repo, String series,
                                    ZonedDateTime start, ZonedDateTime end, long randomSeed) {
        var random = new Random(randomSeed);
        int count = 0;
        ZonedDateTime cursor = start;

        while (cursor.isBefore(end)) {
            double price = generatePrice(cursor, random, series);
            Instant intervalStart = cursor.toInstant();
            MarketDataLookup lookup = new MarketDataLookup(
                    BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP),
                    1L, series, intervalStart, QualityState.VALIDATED);
            repo.saveFixing(TENANT_ID, series, intervalStart, lookup);
            cursor = cursor.plusMinutes(INTERVAL_MINUTES);
            count++;
        }
        return count;
    }

    /**
     * Public entry point for incremental forward curve seeding (called by DataSeeder
     * phase 2b for series that were added after initial seeding).
     */
    public static int seedForwardCurvesPublic(MarketDataRepository repo, String series,
                                               ZonedDateTime start, ZonedDateTime end, long randomSeed) {
        return seedForwardCurves(repo, series, start, end, randomSeed);
    }

    /**
     * Seed 15-min forward curve data (future projected prices) by monthly pillar.
     * Each pillar gets 15-min values representing the expected price shape for that month.
     */
    private static int seedForwardCurves(MarketDataRepository repo, String series,
                                          ZonedDateTime start, ZonedDateTime end, long randomSeed) {
        var random = new Random(randomSeed);
        int count = 0;
        Instant asOfDate = start.toInstant();

        // Iterate month by month
        YearMonth startMonth = YearMonth.from(start.toLocalDate());
        YearMonth endMonth = YearMonth.from(end.toLocalDate());
        YearMonth pillar = startMonth;

        while (!pillar.isAfter(endMonth)) {
            ZonedDateTime monthStart = pillar.atDay(1).atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime monthEnd = pillar.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC);
            if (monthEnd.isAfter(end)) monthEnd = end;

            ZonedDateTime cursor = monthStart;
            while (cursor.isBefore(monthEnd)) {
                double price = generatePrice(cursor, random, series);

                Instant intervalStart = cursor.toInstant();
                MarketDataLookup lookup = new MarketDataLookup(
                        BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP),
                        1L, series, asOfDate, QualityState.VALIDATED);
                repo.saveForwardCurve(TENANT_ID, series, pillar, intervalStart, lookup);
                cursor = cursor.plusMinutes(INTERVAL_MINUTES);
                count++;
            }
            pillar = pillar.plusMonths(1);
        }
        return count;
    }

    /**
     * Seed daily EUR/NOK FX rates.
     */
    private static int seedFxRates(MarketDataRepository repo,
                                    ZonedDateTime start, ZonedDateTime end) {
        var random = new Random(55);
        int count = 0;
        ZonedDateTime cursor = start.toLocalDate().atStartOfDay(ZoneOffset.UTC);

        while (cursor.isBefore(end)) {
            // EUR/NOK ~ 11.2 ± 0.3
            double rate = 11.20 + random.nextGaussian() * 0.30;
            Instant refDate = cursor.toInstant();
            MarketDataLookup lookup = new MarketDataLookup(
                    BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP),
                    1L, "EUR/NOK", refDate, QualityState.VALIDATED);
            repo.saveFxRate(TENANT_ID, "EUR/NOK", refDate, lookup);
            cursor = cursor.plusDays(1);
            count++;
        }
        return count;
    }

    /**
     * Seed monthly HICP-DE index values from Nov 2023 (base) through Jun 2028.
     * Base value ~108.7 (Nov 2023), growing ~2.5% per year.
     */
    private static int seedIndices(MarketDataRepository repo) {
        int count = 0;
        YearMonth base = YearMonth.of(2023, 11);
        YearMonth end = YearMonth.of(2028, 6);
        double baseValue = 108.70;

        YearMonth cursor = base;
        while (!cursor.isAfter(end)) {
            // ~2.5% annual growth = ~0.208% per month
            long monthsFromBase = base.until(cursor, java.time.temporal.ChronoUnit.MONTHS);
            double value = baseValue * Math.pow(1.025, monthsFromBase / 12.0);
            String refMonth = cursor.toString();

            MarketDataLookup lookup = new MarketDataLookup(
                    BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP),
                    1L, "HICP-DE", Instant.now(), QualityState.VALIDATED);
            repo.saveIndex(TENANT_ID, "HICP-DE", refMonth, lookup);
            cursor = cursor.plusMonths(1);
            count++;
        }
        return count;
    }

    // Per-series previous price for mean-reverting walk (reset per seed call via new Random)
    private static final java.util.Map<String, Double> prevPrice = new java.util.concurrent.ConcurrentHashMap<>();

    // EUR constants
    private static final double EUR_BASE = 85.0;
    private static final double EUR_TOL = 5.0;
    private static final double EUR_JITTER_MIN = 1.0;
    private static final double EUR_JITTER_MAX = 2.5;

    // NOK constants (NORDPOOL)
    private static final double NOK_BASE = 950.0;
    private static final double NOK_TOL = 55.0;
    private static final double NOK_JITTER_MIN = 10.0;
    private static final double NOK_JITTER_MAX = 28.0;

    /**
     * Generate a price using a mean-reverting random walk.
     * EUR series: centred on 85 EUR/MWh, range [80, 90], step 1–2.5 EUR.
     * NORDPOOL: centred on 950 NOK/MWh, range [895, 1005], step 10–28 NOK.
     * Direction biased toward centre to prevent boundary sticking.
     */
    private static double generatePrice(ZonedDateTime dt, Random random, String series) {
        boolean isNordpool = "NORDPOOL_SYS".equals(series);
        double base  = isNordpool ? NOK_BASE : EUR_BASE;
        double tol   = isNordpool ? NOK_TOL : EUR_TOL;
        double jmin  = isNordpool ? NOK_JITTER_MIN : EUR_JITTER_MIN;
        double jmax  = isNordpool ? NOK_JITTER_MAX : EUR_JITTER_MAX;

        double prev = prevPrice.getOrDefault(series, base);
        double mag = jmin + random.nextDouble() * (jmax - jmin);
        double dist = (prev - base) / tol; // -1 to +1
        double pDown = 0.5 + 0.35 * dist;
        double direction = random.nextDouble() < pDown ? -1.0 : 1.0;
        double price = prev + direction * mag;
        price = Math.max(base - tol, Math.min(base + tol, price));
        price = Math.round(price * 100.0) / 100.0;
        prevPrice.put(series, price);
        return price;
    }
}
