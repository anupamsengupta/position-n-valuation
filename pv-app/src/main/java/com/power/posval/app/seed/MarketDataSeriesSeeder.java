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
 * Prices follow realistic EU power market patterns:
 * - Night (00–06): 15–35 EUR/MWh (low demand, wind dominant)
 * - Morning ramp (06–09): 35–75 EUR/MWh
 * - Midday solar dip (11–14): 25–55 EUR/MWh (solar oversupply)
 * - Evening peak (17–20): 60–120 EUR/MWh
 * - Weekends: ~30% lower than weekdays
 * - Seasonal: winter +20%, summer -10%
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

        int fxRates = seedFxRates(repo, seriesStart, seriesEnd);
        log.info("Seeded {} EUR/NOK FX rates", fxRates);

        int indices = seedIndices(repo);
        log.info("Seeded {} HICP-DE index values", indices);

        return new int[]{fixings + nordpool, curves, fxRates, indices};
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
                // Forward curves have a slight contango premium over spot
                double premium = 2.0 + random.nextGaussian() * 0.5;
                price += premium;

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

    /**
     * Generate a realistic EU power price for a given timestamp.
     * Models intraday shape, weekend effect, and seasonal variation.
     */
    private static double generatePrice(ZonedDateTime dt, Random random, String series) {
        int hour = dt.getHour();
        int dayOfWeek = dt.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
        int month = dt.getMonthValue();
        boolean isNordpool = "NORDPOOL_SYS".equals(series);

        // Base intraday shape (EUR/MWh)
        double base;
        if (hour >= 0 && hour < 6) {
            // Night: low demand
            base = 22.0 + 8.0 * Math.sin(hour * Math.PI / 6.0);
        } else if (hour >= 6 && hour < 9) {
            // Morning ramp
            base = 35.0 + 25.0 * ((hour - 6) / 3.0);
        } else if (hour >= 9 && hour < 11) {
            // Morning plateau
            base = 55.0 + 10.0 * Math.sin((hour - 9) * Math.PI / 4.0);
        } else if (hour >= 11 && hour < 15) {
            // Midday solar dip
            base = 35.0 + 15.0 * Math.cos((hour - 13) * Math.PI / 4.0);
        } else if (hour >= 15 && hour < 17) {
            // Afternoon recovery
            base = 50.0 + 15.0 * ((hour - 15) / 2.0);
        } else if (hour >= 17 && hour < 21) {
            // Evening peak
            base = 70.0 + 30.0 * Math.sin((hour - 17) * Math.PI / 4.0);
        } else {
            // Late evening decline
            base = 45.0 - 15.0 * ((hour - 21) / 3.0);
        }

        // Weekend discount (~30%)
        if (dayOfWeek >= 6) {
            base *= 0.70;
        }

        // Seasonal variation
        if (month >= 11 || month <= 2) {
            base *= 1.20; // Winter premium
        } else if (month >= 6 && month <= 8) {
            base *= 0.90; // Summer discount
        }

        // NORDPOOL is generally lower than EPEX, in NOK
        if (isNordpool) {
            base = base * 0.60 * 11.2; // Convert to NOK, hydro discount
        }

        // Add noise (±15%)
        double noise = random.nextGaussian() * base * 0.10;

        // Occasional negative prices (2% chance during solar midday in summer)
        if (!isNordpool && month >= 5 && month <= 8 && hour >= 11 && hour < 15
                && random.nextDouble() < 0.02) {
            return -5.0 + random.nextGaussian() * 10.0;
        }

        return Math.max(isNordpool ? 50.0 : 0.5, base + noise);
    }
}
