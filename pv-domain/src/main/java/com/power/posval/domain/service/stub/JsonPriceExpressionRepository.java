package com.power.posval.domain.service.stub;

import com.power.posval.domain.model.expression.*;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import jakarta.inject.Singleton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-resource-backed stub for PriceExpressionRepository.
 * Loads expression trees from classpath:stub/price-expressions.json.
 * Swappable for a real database-backed implementation later.
 */
@Singleton
public class JsonPriceExpressionRepository implements PriceExpressionRepository {

    private static final String RESOURCE = "stub/price-expressions.json";
    private final Map<UUID, PriceExpression> expressions;

    public JsonPriceExpressionRepository() {
        this.expressions = loadExpressions();
    }

    @Override
    public Optional<PriceExpression> findById(UUID id) {
        return Optional.ofNullable(expressions.get(id));
    }

    private Map<UUID, PriceExpression> loadExpressions() {
        var result = new HashMap<UUID, PriceExpression>();
        String json = readResource();
        // Parse the top-level "expressions" array
        List<String> exprBlocks = extractArray(json, "expressions");
        for (String block : exprBlocks) {
            String idStr = extractString(block, "id");
            if (idStr == null) continue;
            UUID id = UUID.fromString(idStr);
            PriceExpression expr = parseExpression(block);
            if (expr != null) {
                result.put(id, expr);
            }
        }
        return Map.copyOf(result);
    }

    PriceExpression parseExpression(String json) {
        String type = extractString(json, "type");
        if (type == null) return null;

        return switch (type) {
            case "ConstantLeaf" -> new ConstantLeaf(
                extractString(json, "leafId"),
                extractDecimal(json, "value"),
                extractString(json, "unit"));

            case "MarketDataLeaf" -> new MarketDataLeaf(
                extractString(json, "leafId"),
                extractString(json, "series"),
                extractString(json, "settlementSeries"),
                extractInt(json, "lag"),
                extractString(json, "quotationWindow"));

            case "IndexLeaf" -> new IndexLeaf(
                extractString(json, "leafId"),
                extractString(json, "series"),
                extractString(json, "refMonthExpression"));

            case "Add" -> new Add(
                parseExpression(extractObject(json, "left")),
                parseExpression(extractObject(json, "right")));

            case "Subtract" -> new Subtract(
                parseExpression(extractObject(json, "left")),
                parseExpression(extractObject(json, "right")));

            case "Multiply" -> new Multiply(
                parseExpression(extractObject(json, "left")),
                parseExpression(extractObject(json, "right")));

            case "Divide" -> new Divide(
                parseExpression(extractObject(json, "numerator")),
                parseExpression(extractObject(json, "denominator")));

            case "Clamp" -> new Clamp(
                parseExpression(extractObject(json, "min")),
                parseExpression(extractObject(json, "max")),
                parseExpression(extractObject(json, "inner")));

            case "Escalate" -> new Escalate(
                parseExpression(extractObject(json, "base")),
                parseExpression(extractObject(json, "ratio")));

            case "ConditionalGate" -> new ConditionalGate(
                parseExpression(extractObject(json, "gateInput")),
                extractString(json, "condition"),
                parseExpression(extractObject(json, "overrideValue")),
                parseExpression(extractObject(json, "inner")));

            case "FxConvert" -> new FxConvert(
                parseExpression(extractObject(json, "value")),
                parseExpression(extractObject(json, "fxRate")));

            default -> null;
        };
    }

    // --- Minimal JSON parsing without external dependencies ---

    private String readResource() {
        try (var is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) return "{}";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    static String extractString(String json, String key) {
        // Only match at depth 1 (direct children of the outermost object)
        String target = "\"" + key + "\"";
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') { depth++; continue; }
            if (c == '}' || c == ']') { depth--; continue; }
            if (depth == 1 && c == '"' && json.startsWith(target, i)) {
                int afterKey = i + target.length();
                // Skip whitespace and colon
                int ci = afterKey;
                while (ci < json.length() && (json.charAt(ci) == ' ' || json.charAt(ci) == ':' || json.charAt(ci) == '\n' || json.charAt(ci) == '\r' || json.charAt(ci) == '\t')) ci++;
                if (ci < json.length()) {
                    if (json.charAt(ci) == '"') {
                        int valEnd = json.indexOf('"', ci + 1);
                        return valEnd > ci ? json.substring(ci + 1, valEnd) : null;
                    }
                    if (json.startsWith("null", ci)) return null;
                }
            }
        }
        return null;
    }

    static BigDecimal extractDecimal(String json, String key) {
        // Only match at depth 1
        String target = "\"" + key + "\"";
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') { depth++; continue; }
            if (c == '}' || c == ']') { depth--; continue; }
            if (depth == 1 && c == '"' && json.startsWith(target, i)) {
                int afterKey = i + target.length();
                Matcher m = Pattern.compile("\\s*:\\s*(-?[0-9]+\\.?[0-9]*)").matcher(json.substring(afterKey));
                if (m.lookingAt()) return new BigDecimal(m.group(1));
            }
        }
        return BigDecimal.ZERO;
    }

    static int extractInt(String json, String key) {
        // Only match at depth 1
        String target = "\"" + key + "\"";
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') { depth++; continue; }
            if (c == '}' || c == ']') { depth--; continue; }
            if (depth == 1 && c == '"' && json.startsWith(target, i)) {
                int afterKey = i + target.length();
                Matcher m = Pattern.compile("\\s*:\\s*(-?[0-9]+)").matcher(json.substring(afterKey));
                if (m.lookingAt()) return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    static String extractObject(String json, String key) {
        String target = "\"" + key + "\"";
        // Find the key at depth 1 only (direct child of outermost object)
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') { depth++; continue; }
            if (c == '}' || c == ']') { depth--; continue; }
            if (depth == 1 && c == '"' && json.startsWith(target, i)) {
                // Found key at depth 1 — now find the value object
                int afterKey = i + target.length();
                int colonIdx = json.indexOf(':', afterKey);
                if (colonIdx < 0) return "{}";
                int braceStart = json.indexOf('{', colonIdx);
                if (braceStart < 0) return "{}";
                int bd = 0;
                for (int j = braceStart; j < json.length(); j++) {
                    if (json.charAt(j) == '{') bd++;
                    else if (json.charAt(j) == '}') {
                        bd--;
                        if (bd == 0) return json.substring(braceStart, j + 1);
                    }
                }
                return "{}";
            }
        }
        return "{}";
    }

    static List<String> extractArray(String json, String key) {
        String prefix = "\"" + key + "\"";
        int keyIdx = json.indexOf(prefix);
        if (keyIdx < 0) return List.of();
        int colonIdx = json.indexOf(':', keyIdx + prefix.length());
        if (colonIdx < 0) return List.of();
        int bracketStart = json.indexOf('[', colonIdx);
        if (bracketStart < 0) return List.of();

        var result = new ArrayList<String>();
        int depth = 0;
        int objectStart = -1;
        for (int i = bracketStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objectStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    result.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return result;
    }
}
