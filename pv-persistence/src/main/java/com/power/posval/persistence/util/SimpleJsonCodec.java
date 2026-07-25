package com.power.posval.persistence.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON codec for JSONB columns storing Set&lt;String&gt; and Map&lt;String, Long&gt;.
 * No external dependencies — string manipulation only.
 * Used by JPA adapters to round-trip activeLeaves, inputVersionSet, curveVersionSet, volumeVersionSet.
 */
public final class SimpleJsonCodec {

    private SimpleJsonCodec() {}

    /**
     * Serializes a Set&lt;String&gt; to a JSON array string.
     * Example: {"EPEX_DA15", "SPREAD_5"} → ["EPEX_DA15","SPREAD_5"]
     */
    public static String setToJson(Set<String> set) {
        if (set == null || set.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        var sorted = new ArrayList<>(set);
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(sorted.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Serializes a Map&lt;String, Long&gt; to a JSON object string.
     * Example: {EPEX_DA15=1} → {"EPEX_DA15":1}
     */
    public static String mapToJson(Map<String, Long> map) {
        if (map == null || map.isEmpty()) return "{}";
        var sb = new StringBuilder("{");
        var sorted = new ArrayList<>(map.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(sorted.get(i).getKey())).append('"');
            sb.append(':').append(sorted.get(i).getValue());
        }
        return sb.append('}').toString();
    }

    /**
     * Deserializes a JSON array string to Set&lt;String&gt;.
     * Handles: ["a","b"], [], null, empty string.
     */
    public static Set<String> jsonToStringSet(String json) {
        if (json == null || json.isBlank()) return Set.of();
        String trimmed = json.trim();
        if (trimmed.equals("[]") || trimmed.equals("null")) return Set.of();

        var result = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(trimmed);
        while (m.find()) {
            result.add(unescapeJson(m.group(1)));
        }
        return Set.copyOf(result);
    }

    /**
     * Deserializes a JSON object string to Map&lt;String, Long&gt;.
     * Handles: {"k":1,"k2":2}, {}, null, empty string.
     */
    public static Map<String, Long> jsonToStringLongMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        String trimmed = json.trim();
        if (trimmed.equals("{}") || trimmed.equals("null")) return Map.of();

        var result = new LinkedHashMap<String, Long>();
        Matcher m = Pattern.compile("\"([^\"]*)\"\\s*:\\s*(-?\\d+)").matcher(trimmed);
        while (m.find()) {
            result.put(unescapeJson(m.group(1)), Long.parseLong(m.group(2)));
        }
        return Map.copyOf(result);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
