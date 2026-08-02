package com.power.posval.domain.service.stub;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
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
 * <h3>Internal data structures — optimized for lookup, not storage</h3>
 *
 * <p>All timestamp keys are stored as primitive {@code long} epoch-millis.
 * Data is held in array-based structures instead of Maps, eliminating boxing,
 * hashing, and tree-node overhead on every lookup.
 *
 * <table>
 *   <tr><th>Lookup type</th><th>Structure</th><th>Complexity</th><th>Why</th></tr>
 *   <tr>
 *     <td>Fixings (exact-match, regular interval)</td>
 *     <td>{@link FixingArray} — flat {@code BigDecimal[]} indexed by
 *         {@code (millis - base) / interval}</td>
 *     <td>O(1) — single subtraction + division, no hash, no compare</td>
 *     <td>15-min fixings are perfectly regular; array index = arithmetic</td>
 *   </tr>
 *   <tr>
 *     <td>Forward curves / FX (floor-match, irregular interval)</td>
 *     <td>{@link FloorArray} — sorted primitive {@code long[]} +
 *         parallel {@code BigDecimal[]}, binary search</td>
 *     <td>O(log n) — {@code Arrays.binarySearch} on primitive {@code long[]},
 *         zero boxing during search</td>
 *     <td>Daily snapshots have gaps (weekends/holidays); binary search on
 *         primitive array is faster than {@code TreeMap.floorEntry} on boxed Long</td>
 *   </tr>
 * </table>
 */
@Singleton
public class JsonFixingArrayBasedMarketDataPort implements MarketDataPort {

    private static final String RESOURCE = "stub/market-data.json";

    // series -> FixingArray (direct index: O(1), zero boxing)
    private final Map<String, FixingArray> fixings;
    // series -> pillar -> FloorArray (binary search on long[]: O(log n), zero boxing)
    private final Map<String, Map<String, FloorArray>> forwardCurves;
    // series -> refMonth -> value (small map, few lookups)
    private final Map<String, Map<String, BigDecimal>> indices;
    // pair -> FloorArray (binary search on long[]: O(log n), zero boxing)
    private final Map<String, FloorArray> fxRates;

    public JsonFixingArrayBasedMarketDataPort() {
        String json = readResource();
        this.fixings = parseFixings(json);
        this.forwardCurves = parseForwardCurves(json);
        this.indices = parseIndices(json);
        this.fxRates = parseFxRates(json);
    }

    @Override
    public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
        var arr = fixings.get(series);
        BigDecimal value = (arr != null) ? arr.get(intervalStart.toEpochMilli()) : BigDecimal.ZERO;
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
        var arr = seriesData.get(pillar.toString());
        BigDecimal value = (arr != null) ? arr.floor(asOfDate.toEpochMilli()) : BigDecimal.ZERO;
        return new MarketDataLookup(value, 1L, series, asOfDate, QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
        var arr = fxRates.get(currencyPair);
        BigDecimal rate = (arr != null) ? arr.floor(referenceDate.toEpochMilli()) : BigDecimal.ONE;
        return new MarketDataLookup(rate, 1L, currencyPair, referenceDate, QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long versionId) {
        return lookupFixing(series, intervalStart);
    }

    @Override
    public VolSurfaceLookup lookupVolSurface(String surfaceId, double strikeDelta,
                                              String expiryTenor, Instant asOfDate) {
        return new VolSurfaceLookup(BigDecimal.ZERO, 1L, surfaceId, strikeDelta,
            expiryTenor, asOfDate, QualityState.VALIDATED);
    }

    @Override
    public MarketDataLookup lookupSpread(String series, Instant intervalStart) {
        return new MarketDataLookup(BigDecimal.ZERO, 1L, series, intervalStart,
            QualityState.VALIDATED);
    }

    // -----------------------------------------------------------------------
    //  Array-based lookup structures
    // -----------------------------------------------------------------------

    /**
     * Regular-interval fixing series stored as a flat array.
     * Lookup = {@code (millis - base) / interval} → array index.
     * O(1), zero boxing, zero hashing, zero comparison.
     *
     * <p>If the series has only one data point, or the interval is irregular,
     * falls back to a single-element array with exact-match semantics.
     */
    static final class FixingArray {
        final long baseMillis;      // epoch-millis of the first data point
        final long intervalMillis;  // detected from first two entries (e.g., 900_000 for 15-min)
        final BigDecimal[] values;

        FixingArray(long baseMillis, long intervalMillis, BigDecimal[] values) {
            this.baseMillis = baseMillis;
            this.intervalMillis = intervalMillis;
            this.values = values;
        }

        BigDecimal get(long epochMillis) {
            if (intervalMillis <= 0) {
                // Degenerate: single point or unable to detect interval
                return (epochMillis == baseMillis && values.length > 0) ? values[0] : BigDecimal.ZERO;
            }
            long offset = epochMillis - baseMillis;
            if (offset < 0 || offset % intervalMillis != 0) return BigDecimal.ZERO;
            int index = (int) (offset / intervalMillis);
            if (index >= values.length) return BigDecimal.ZERO;
            BigDecimal v = values[index];
            return (v != null) ? v : BigDecimal.ZERO;
        }

        static FixingArray build(TreeMap<Long, BigDecimal> sorted) {
            if (sorted.isEmpty()) return new FixingArray(0, 0, new BigDecimal[0]);
            if (sorted.size() == 1) {
                var e = sorted.firstEntry();
                return new FixingArray(e.getKey(), 0, new BigDecimal[]{e.getValue()});
            }

            long base = sorted.firstKey();
            long last = sorted.lastKey();

            // Detect interval from first two timestamps
            var it = sorted.keySet().iterator();
            it.next();
            long second = it.next();
            long interval = second - base;

            // Array size = (last - base) / interval + 1
            int size = (int) ((last - base) / interval) + 1;

            // Safety: cap at 10M slots to avoid accidental huge allocation
            if (size > 10_000_000 || interval <= 0) {
                // Irregular data — fall back to FloorArray semantics via single-slot
                return new FixingArray(base, 0, new BigDecimal[]{sorted.get(base)});
            }

            BigDecimal[] arr = new BigDecimal[size];
            for (var entry : sorted.entrySet()) {
                long offset = entry.getKey() - base;
                if (offset % interval == 0) {
                    int idx = (int) (offset / interval);
                    if (idx >= 0 && idx < size) {
                        arr[idx] = entry.getValue();
                    }
                }
            }
            return new FixingArray(base, interval, arr);
        }
    }

    /**
     * Irregularly-spaced time series stored as parallel sorted primitive arrays.
     * Lookup = {@code Arrays.binarySearch(long[])} → floor index.
     * O(log n) on primitive {@code long[]} — no boxing during search.
     *
     * <p>Compared to {@code TreeMap<Long, BigDecimal>}:
     * <ul>
     *   <li>No boxed Long keys (saves 24 bytes/entry + GC pressure)</li>
     *   <li>No TreeMap.Entry nodes (saves 48+ bytes/entry)</li>
     *   <li>CPU-cache-friendly: contiguous long[] scanned sequentially by binarySearch</li>
     * </ul>
     */
    static final class FloorArray {
        final long[] timestamps;     // sorted ascending, primitive
        final BigDecimal[] values;   // parallel array, same length

        FloorArray(long[] timestamps, BigDecimal[] values) {
            this.timestamps = timestamps;
            this.values = values;
        }

        /** Returns the value at the greatest timestamp <= epochMillis, or the fallback. */
        BigDecimal floor(long epochMillis) {
            if (timestamps.length == 0) return BigDecimal.ZERO;
            int idx = Arrays.binarySearch(timestamps, epochMillis);
            if (idx >= 0) return values[idx];              // exact match
            int insertionPoint = -idx - 1;
            if (insertionPoint == 0) return BigDecimal.ZERO; // before first entry
            return values[insertionPoint - 1];              // floor
        }

        static FloorArray build(TreeMap<Long, BigDecimal> sorted) {
            long[] ts = new long[sorted.size()];
            BigDecimal[] vals = new BigDecimal[sorted.size()];
            int i = 0;
            for (var entry : sorted.entrySet()) {
                ts[i] = entry.getKey();
                vals[i] = entry.getValue();
                i++;
            }
            return new FloorArray(ts, vals);
        }
    }

    // -----------------------------------------------------------------------
    //  JSON parsing
    // -----------------------------------------------------------------------

    private String readResource() {
        try (var is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) return "{}";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, FixingArray> parseFixings(String json) {
        var result = new HashMap<String, FixingArray>();
        String fixingsBlock = extractObject(json, "fixings");
        for (String seriesName : extractKeys(fixingsBlock)) {
            String seriesBlock = extractObject(fixingsBlock, seriesName);
            var sorted = new TreeMap<Long, BigDecimal>();
            for (var entry : extractKeyValuePairs(seriesBlock)) {
                sorted.put(Instant.parse(entry.getKey()).toEpochMilli(), new BigDecimal(entry.getValue()));
            }
            result.put(seriesName, FixingArray.build(sorted));
        }
        return Map.copyOf(result);
    }

    private Map<String, Map<String, FloorArray>> parseForwardCurves(String json) {
        var result = new HashMap<String, Map<String, FloorArray>>();
        String curvesBlock = extractObject(json, "forwardCurves");
        for (String seriesName : extractKeys(curvesBlock)) {
            String seriesBlock = extractObject(curvesBlock, seriesName);
            var pillarMap = new HashMap<String, FloorArray>();
            for (String pillar : extractKeys(seriesBlock)) {
                String pillarBlock = extractObject(seriesBlock, pillar);
                var sorted = new TreeMap<Long, BigDecimal>();
                for (var entry : extractKeyValuePairs(pillarBlock)) {
                    sorted.put(Instant.parse(entry.getKey()).toEpochMilli(), new BigDecimal(entry.getValue()));
                }
                pillarMap.put(pillar, FloorArray.build(sorted));
            }
            result.put(seriesName, Map.copyOf(pillarMap));
        }
        return Map.copyOf(result);
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

    private Map<String, FloorArray> parseFxRates(String json) {
        var result = new HashMap<String, FloorArray>();
        String fxBlock = extractObject(json, "fxRates");
        for (String pairName : extractKeys(fxBlock)) {
            String pairBlock = extractObject(fxBlock, pairName);
            var sorted = new TreeMap<Long, BigDecimal>();
            for (var entry : extractKeyValuePairs(pairBlock)) {
                sorted.put(Instant.parse(entry.getKey()).toEpochMilli(), new BigDecimal(entry.getValue()));
            }
            result.put(pairName, FloorArray.build(sorted));
        }
        return Map.copyOf(result);
    }

    // -----------------------------------------------------------------------
    //  Minimal JSON extraction (no external dependency)
    // -----------------------------------------------------------------------

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
                Matcher km = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(json.substring(i));
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
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)");
        Matcher m = p.matcher(json);
        while (m.find()) {
            pairs.add(Map.entry(m.group(1), m.group(2)));
        }
        return pairs;
    }
}
