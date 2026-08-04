package com.power.posval.app.seed;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String TENANT_ID = "default";

    private final MarketDataRepository marketDataRepo;
    private final VolumeSeriesRepository volumeSeriesRepo;
    private final TransactionalExecutor txExecutor;
    private final boolean seedEnabled;

    public DataSeeder(MarketDataRepository marketDataRepo,
                       VolumeSeriesRepository volumeSeriesRepo,
                       TransactionalExecutor txExecutor,
                       @Value("${pv.seed.enabled:true}") boolean seedEnabled) {
        this.marketDataRepo = marketDataRepo;
        this.volumeSeriesRepo = volumeSeriesRepo;
        this.txExecutor = txExecutor;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("Data seeding disabled");
            return;
        }

        // --- Phase 1: JSON-based market data (legacy fixings, curves from stub file) ---
        boolean jsonSeeded = txExecutor.execute(
                () -> marketDataRepo.findFixing(TENANT_ID, "EPEX_DA15",
                        Instant.parse("2025-03-01T00:00:00Z")).isPresent());
        if (jsonSeeded) {
            log.info("JSON market data already seeded, skipping");
        } else {
            log.info("Seeding market data from stub/market-data.json...");
            String json = readResource();
            int[] counts = new int[4];
            txExecutor.run(() -> {
                counts[0] = loadFixings(json);
                counts[1] = loadForwardCurves(json);
                counts[2] = loadIndices(json);
                counts[3] = loadFxRates(json);
            });
            log.info("Seeded {} fixings, {} forward curves, {} indices, {} FX rates",
                    counts[0], counts[1], counts[2], counts[3]);
        }

        // --- Phase 2: 15-min market data (Jul 2026 → Jul 2028) ---
        boolean seriesSeeded = txExecutor.execute(
                () -> marketDataRepo.findFixing(TENANT_ID, "EPEX_DA15",
                        Instant.parse("2026-07-01T00:00:00Z")).isPresent());
        if (seriesSeeded) {
            log.info("15-min market data series already seeded, skipping");
        } else {
            log.info("Seeding 15-min market data (fixings Jul 2026 → now, curves now → Jul 2028)...");
            int[] mktCounts = txExecutor.execute(() -> MarketDataSeriesSeeder.seed(marketDataRepo));
            log.info("Seeded {} fixings+nordpool, {} forward curve points, {} FX rates, {} indices",
                    mktCounts[0], mktCounts[1], mktCounts[2], mktCounts[3]);
        }

        // --- Phase 3: Volume series (wind + solar, Jul 2026 → Jul 2028, 15-min UTC) ---
        boolean volumeExists = txExecutor.execute(
                () -> volumeSeriesRepo.findCurrentBySeriesKey(TENANT_ID,
                        VolumeSeriesSeeder.WIND_SERIES_KEY.value()).isPresent());
        if (volumeExists) {
            log.info("Volume series already seeded, skipping");
        } else {
            log.info("Seeding volume series (Jul 2026 → Jul 2028, 15-min UTC)...");
            int[] volCounts = txExecutor.execute(() -> VolumeSeriesSeeder.seed(volumeSeriesRepo));
            log.info("Seeded {} wind intervals, {} solar intervals", volCounts[0], volCounts[1]);
        }
    }

    private int loadFixings(String json) {
        int count = 0;
        String fixingsBlock = extractObject(json, "fixings");
        for (String seriesName : extractKeys(fixingsBlock)) {
            String seriesBlock = extractObject(fixingsBlock, seriesName);
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                Instant intervalStart = Instant.parse(entry.getKey());
                BigDecimal value = new BigDecimal(entry.getValue());
                MarketDataLookup lookup = new MarketDataLookup(
                        value, 1L, seriesName, intervalStart, QualityState.VALIDATED);
                marketDataRepo.saveFixing(TENANT_ID, seriesName, intervalStart, lookup);
                count++;
            }
        }
        return count;
    }

    private int loadForwardCurves(String json) {
        int count = 0;
        String curvesBlock = extractObject(json, "forwardCurves");
        for (String seriesName : extractKeys(curvesBlock)) {
            String seriesBlock = extractObject(curvesBlock, seriesName);
            for (String pillarStr : extractKeys(seriesBlock)) {
                YearMonth pillar = YearMonth.parse(pillarStr);
                String pillarBlock = extractObject(seriesBlock, pillarStr);
                for (var entry : extractKeyValuePairs(pillarBlock)) {
                    Instant asOfDate = Instant.parse(entry.getKey());
                    BigDecimal value = new BigDecimal(entry.getValue());
                    MarketDataLookup lookup = new MarketDataLookup(
                            value, 1L, seriesName, asOfDate, QualityState.VALIDATED);
                    marketDataRepo.saveForwardCurve(TENANT_ID, seriesName, pillar, asOfDate, lookup);
                    count++;
                }
            }
        }
        return count;
    }

    private int loadIndices(String json) {
        int count = 0;
        String indicesBlock = extractObject(json, "indices");
        for (String seriesName : extractKeys(indicesBlock)) {
            String seriesBlock = extractObject(indicesBlock, seriesName);
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                String refMonth = entry.getKey();
                BigDecimal value = new BigDecimal(entry.getValue());
                MarketDataLookup lookup = new MarketDataLookup(
                        value, 1L, seriesName, Instant.now(), QualityState.VALIDATED);
                marketDataRepo.saveIndex(TENANT_ID, seriesName, refMonth, lookup);
                count++;
            }
        }
        return count;
    }

    private int loadFxRates(String json) {
        int count = 0;
        String fxBlock = extractObject(json, "fxRates");
        for (String pair : extractKeys(fxBlock)) {
            String pairBlock = extractObject(fxBlock, pair);
            for (var entry : extractKeyValuePairs(pairBlock)) {
                Instant refDate = Instant.parse(entry.getKey());
                BigDecimal rate = new BigDecimal(entry.getValue());
                MarketDataLookup lookup = new MarketDataLookup(
                        rate, 1L, pair, refDate, QualityState.VALIDATED);
                marketDataRepo.saveFxRate(TENANT_ID, pair, refDate, lookup);
                count++;
            }
        }
        return count;
    }

    private static String readResource() {
        try (var is = DataSeeder.class.getClassLoader()
                .getResourceAsStream("stub/market-data.json")) {
            if (is == null) throw new IllegalStateException("stub/market-data.json not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read market-data.json", e);
        }
    }

    // --- JSON extraction (same logic as MarketDataDbLoader) ---

    static String extractObject(String json, String key) {
        String prefix = "\"" + key + "\"";
        int keyIdx = json.indexOf(prefix);
        if (keyIdx < 0) return "{}";
        int colonIdx = json.indexOf(':', keyIdx + prefix.length());
        if (colonIdx < 0) return "{}";
        int braceStart = json.indexOf('{', colonIdx);
        if (braceStart < 0) return "{}";
        int depth = 0;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(braceStart, i + 1);
            }
        }
        return "{}";
    }

    static List<String> extractKeys(String json) {
        var keys = new ArrayList<String>();
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { depth++; continue; }
            if (c == '}') { depth--; continue; }
            if (depth == 1 && c == '"') {
                Matcher km = Pattern.compile("\"([^\"]+)\"\\s*:")
                        .matcher(json.substring(i));
                if (km.lookingAt()) {
                    keys.add(km.group(1));
                    i += km.end() - 1;
                    int vi = i + 1;
                    while (vi < json.length() && Character.isWhitespace(json.charAt(vi))) vi++;
                    if (vi < json.length() && json.charAt(vi) == '{') {
                        int vd = 0;
                        for (int j = vi; j < json.length(); j++) {
                            if (json.charAt(j) == '{') vd++;
                            else if (json.charAt(j) == '}') {
                                vd--;
                                if (vd == 0) { i = j; break; }
                            }
                        }
                    }
                }
            }
        }
        return keys;
    }

    static List<Map.Entry<String, String>> extractKeyValuePairs(String json) {
        var pairs = new ArrayList<Map.Entry<String, String>>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)")
                .matcher(json);
        while (m.find()) {
            pairs.add(Map.entry(m.group(1), m.group(2)));
        }
        return pairs;
    }
}
