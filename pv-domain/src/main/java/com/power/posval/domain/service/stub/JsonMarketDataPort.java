package com.power.posval.domain.service.stub;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-resource-backed stub for MarketDataPort.
 * Loads fixings, forward curves, indices, and FX rates from classpath:stub/market-data.json.
 * Swappable for a real market data service later.
 *
 * <p>Internal keys use {@code long} epoch-millis instead of {@link Instant} objects.
 * This avoids per-lookup object allocation and gives faster hash/compare:
 * <ul>
 *   <li>HashMap: {@code Long.hashCode()} = single XOR vs {@code Instant.hashCode()} = field access + XOR</li>
 *   <li>TreeMap: {@code Long.compare()} = single CPU instruction vs {@code Instant.compareTo()} = two-field compare</li>
 *   <li>Memory: boxed Long = 24 bytes vs Instant = 32 bytes per key</li>
 * </ul>
 */
@Singleton
public class JsonMarketDataPort implements MarketDataPort {

    private static final String RESOURCE = "stub/market-data.json";

    // series -> epochMillis -> value (HashMap: exact-match lookup, O(1))
    private final Map<String, Map<Long, BigDecimal>> fixings;
    // series -> pillar -> epochMillis -> value (TreeMap: floorEntry lookup, O(log n))
    private final Map<String, Map<String, TreeMap<Long, BigDecimal>>> forwardCurves;
    // series -> refMonth -> value
    private final Map<String, Map<String, BigDecimal>> indices;
    // pair -> epochMillis -> rate (TreeMap: floorEntry lookup, O(log n))
    private final Map<String, TreeMap<Long, BigDecimal>> fxRates;

    public JsonMarketDataPort() {
        String json = readResource();
        this.fixings = parseFixings(json);
        this.forwardCurves = parseForwardCurves(json);
        this.indices = parseIndices(json);
        this.fxRates = parseFxRates(json);
    }

    @Override
    public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
        var seriesData = fixings.getOrDefault(series, Map.of());
        BigDecimal value = seriesData.getOrDefault(intervalStart.toEpochMilli(), BigDecimal.ZERO);
        return new MarketDataLookup(value, 1L, series, intervalStart, QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupIndex(String series, String refMonthExpression, DeliveryPeriod deliveryPeriod) {
        var seriesData = indices.getOrDefault(series, Map.of());
        BigDecimal value = seriesData.getOrDefault(refMonthExpression, BigDecimal.ZERO);
        return new MarketDataLookup(value, 1L, series,
            deliveryPeriod.start().toInstant(), QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar, Instant asOfDate) {
        var seriesData = forwardCurves.getOrDefault(series, Map.of());
        var pillarData = seriesData.get(pillar.toString());
        // O(log n) — binary search on long keys for latest as-of date <= asOfDate
        BigDecimal value = BigDecimal.ZERO;
        if (pillarData != null) {
            var floor = pillarData.floorEntry(asOfDate.toEpochMilli());
            if (floor != null) value = floor.getValue();
        }
        return new MarketDataLookup(value, 1L, series, asOfDate, QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
        var pairData = fxRates.get(currencyPair);
        // O(log n) — binary search on long keys for latest rate <= referenceDate
        BigDecimal rate = BigDecimal.ONE;
        if (pairData != null) {
            var floor = pairData.floorEntry(referenceDate.toEpochMilli());
            if (floor != null) rate = floor.getValue();
        }
        return new MarketDataLookup(rate, 1L, currencyPair, referenceDate, QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long versionId) {
        return lookupFixing(series, intervalStart);
    }

    // --- JSON parsing ---

    private String readResource() {
        try (var is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) return "{}";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Map<Long, BigDecimal>> parseFixings(String json) {
        var result = new HashMap<String, Map<Long, BigDecimal>>();
        String fixingsBlock = extractObject(json, "fixings");
        for (String seriesName : extractKeys(fixingsBlock)) {
            String seriesBlock = extractObject(fixingsBlock, seriesName);
            var seriesMap = new HashMap<Long, BigDecimal>();
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                seriesMap.put(Instant.parse(entry.getKey()).toEpochMilli(), new BigDecimal(entry.getValue()));
            }
            result.put(seriesName, Map.copyOf(seriesMap));
        }
        return Map.copyOf(result);
    }

    private Map<String, Map<String, TreeMap<Long, BigDecimal>>> parseForwardCurves(String json) {
        var result = new HashMap<String, Map<String, TreeMap<Long, BigDecimal>>>();
        String curvesBlock = extractObject(json, "forwardCurves");
        for (String seriesName : extractKeys(curvesBlock)) {
            String seriesBlock = extractObject(curvesBlock, seriesName);
            var pillarMap = new HashMap<String, TreeMap<Long, BigDecimal>>();
            for (String pillar : extractKeys(seriesBlock)) {
                String pillarBlock = extractObject(seriesBlock, pillar);
                var asOfMap = new TreeMap<Long, BigDecimal>();
                for (var entry : extractKeyValuePairs(pillarBlock)) {
                    asOfMap.put(Instant.parse(entry.getKey()).toEpochMilli(), new BigDecimal(entry.getValue()));
                }
                pillarMap.put(pillar, asOfMap);
            }
            result.put(seriesName, Collections.unmodifiableMap(pillarMap));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Map<String, BigDecimal>> parseIndices(String json) {
        var result = new HashMap<String, Map<String, BigDecimal>>();
        String indicesBlock = extractObject(json, "indices");
        for (String seriesName : extractKeys(indicesBlock)) {
            String seriesBlock = extractObject(indicesBlock, seriesName);
            var refMap = new HashMap<String, BigDecimal>();
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                refMap.put(entry.getKey(), new BigDecimal(entry.getValue()));
            }
            result.put(seriesName, Map.copyOf(refMap));
        }
        return Map.copyOf(result);
    }

    private Map<String, TreeMap<Long, BigDecimal>> parseFxRates(String json) {
        var result = new HashMap<String, TreeMap<Long, BigDecimal>>();
        String fxBlock = extractObject(json, "fxRates");
        for (String pairName : extractKeys(fxBlock)) {
            String pairBlock = extractObject(fxBlock, pairName);
            var rateMap = new TreeMap<Long, BigDecimal>();
            for (var entry : extractKeyValuePairs(pairBlock)) {
                rateMap.put(Instant.parse(entry.getKey()).toEpochMilli(), new BigDecimal(entry.getValue()));
            }
            result.put(pairName, rateMap);
        }
        return Collections.unmodifiableMap(result);
    }

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
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:");
        Matcher m = p.matcher(json);
        // Skip the outermost braces to find direct children
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}') {
                depth--;
                continue;
            }
            if (depth == 1 && c == '"') {
                // Try to match a key at this position
                Matcher km = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(json.substring(i));
                if (km.lookingAt()) {
                    keys.add(km.group(1));
                    i += km.end() - 1;
                    // Skip the value
                    int vi = i + 1;
                    while (vi < json.length() && Character.isWhitespace(json.charAt(vi))) vi++;
                    if (vi < json.length()) {
                        char vc = json.charAt(vi);
                        if (vc == '{') {
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
        }
        return keys;
    }

    static List<Map.Entry<String, String>> extractKeyValuePairs(String json) {
        var pairs = new ArrayList<Map.Entry<String, String>>();
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)");
        Matcher m = p.matcher(json);
        while (m.find()) {
            pairs.add(Map.entry(m.group(1), m.group(2)));
        }
        return pairs;
    }
}
