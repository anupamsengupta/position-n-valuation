package com.power.posval.integration.support;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.repository.MarketDataRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses stub/market-data.json and saves all entries via JpaMarketDataRepository.
 * Reuses the same JSON extraction approach as JsonMarketDataPort.
 */
public final class MarketDataDbLoader {

    private MarketDataDbLoader() {}

    /**
     * Load all market data from stub/market-data.json into the database.
     * Must be called within a transaction.
     */
    public static void load(MarketDataRepository repo, String tenantId) {
        String json = readResource();
        loadFixings(json, repo, tenantId);
        loadForwardCurves(json, repo, tenantId);
        loadIndices(json, repo, tenantId);
        loadFxRates(json, repo, tenantId);
    }

    private static void loadFixings(String json, MarketDataRepository repo, String tenantId) {
        String fixingsBlock = extractObject(json, "fixings");
        for (String seriesName : extractKeys(fixingsBlock)) {
            String seriesBlock = extractObject(fixingsBlock, seriesName);
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                Instant intervalStart = Instant.parse(entry.getKey());
                BigDecimal value = new BigDecimal(entry.getValue());
                MarketDataLookup lookup = new MarketDataLookup(
                    value, 1L, seriesName, intervalStart, QualityState.VALIDATED);
                repo.saveFixing(tenantId, seriesName, intervalStart, lookup);
            }
        }
    }

    private static void loadForwardCurves(String json, MarketDataRepository repo, String tenantId) {
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
                    repo.saveForwardCurve(tenantId, seriesName, pillar, asOfDate, lookup);
                }
            }
        }
    }

    private static void loadIndices(String json, MarketDataRepository repo, String tenantId) {
        String indicesBlock = extractObject(json, "indices");
        for (String seriesName : extractKeys(indicesBlock)) {
            String seriesBlock = extractObject(indicesBlock, seriesName);
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                String refMonth = entry.getKey();
                BigDecimal value = new BigDecimal(entry.getValue());
                MarketDataLookup lookup = new MarketDataLookup(
                    value, 1L, seriesName, Instant.now(), QualityState.VALIDATED);
                repo.saveIndex(tenantId, seriesName, refMonth, lookup);
            }
        }
    }

    private static void loadFxRates(String json, MarketDataRepository repo, String tenantId) {
        String fxBlock = extractObject(json, "fxRates");
        for (String pair : extractKeys(fxBlock)) {
            String pairBlock = extractObject(fxBlock, pair);
            for (var entry : extractKeyValuePairs(pairBlock)) {
                Instant refDate = Instant.parse(entry.getKey());
                BigDecimal rate = new BigDecimal(entry.getValue());
                MarketDataLookup lookup = new MarketDataLookup(
                    rate, 1L, pair, refDate, QualityState.VALIDATED);
                repo.saveFxRate(tenantId, pair, refDate, lookup);
            }
        }
    }

    private static String readResource() {
        try (var is = MarketDataDbLoader.class.getClassLoader()
                .getResourceAsStream("stub/market-data.json")) {
            if (is == null) throw new IllegalStateException("stub/market-data.json not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read market-data.json", e);
        }
    }

    // --- JSON extraction (same logic as JsonMarketDataPort) ---

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
