package com.power.posval.domain.service.stub;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.repository.MeteredActualRepository;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-resource-backed stub for MeteredActualRepository.
 * Loads metered actual data from classpath:stub/metered-actuals.json.
 * Swappable for a real database-backed implementation later.
 */
@Singleton
public class JsonMeteredActualRepository implements MeteredActualRepository {

    private static final String RESOURCE = "stub/metered-actuals.json";
    private final Map<String, MeteredActualVolumeSeries> seriesByKey;

    public JsonMeteredActualRepository() {
        this.seriesByKey = loadSeries();
    }

    @Override
    public Optional<MeteredActualVolumeSeries> findCurrentBySeriesKey(String tenantId, SeriesKey seriesKey) {
        return Optional.ofNullable(seriesByKey.get(seriesKey.value()));
    }

    private Map<String, MeteredActualVolumeSeries> loadSeries() {
        var result = new HashMap<String, MeteredActualVolumeSeries>();
        String json = readResource();
        List<String> seriesBlocks = extractArray(json, "series");

        for (String block : seriesBlocks) {
            String key = extractString(block, "seriesKey");
            if (key == null) continue;

            String tzStr = extractString(block, "deliveryTimezone");
            ZoneId tz = tzStr != null ? ZoneId.of(tzStr) : ZoneId.of("Europe/Berlin");

            String startStr = extractString(block, "deliveryStart");
            String endStr = extractString(block, "deliveryEnd");
            ZonedDateTime start = startStr != null ? ZonedDateTime.parse(startStr) : ZonedDateTime.now(tz);
            ZonedDateTime end = endStr != null ? ZonedDateTime.parse(endStr) : start.plusMonths(1);

            String qsStr = extractString(block, "qualityState");
            QualityState qs = qsStr != null ? QualityState.valueOf(qsStr) : QualityState.PROVISIONAL;

            String msStr = extractString(block, "materializationStatus");
            MaterializationStatus ms = msStr != null
                ? MaterializationStatus.valueOf(msStr) : MaterializationStatus.PENDING;

            String mtStr = extractString(block, "materializedThrough");
            YearMonth mt = mtStr != null ? YearMonth.parse(mtStr) : null;

            String txStr = extractString(block, "transactionTime");
            Instant txTime = txStr != null ? Instant.parse(txStr) : Instant.now();

            String rxStr = extractString(block, "receivedAt");
            Instant rxTime = rxStr != null ? Instant.parse(rxStr) : txTime;

            // Parse intervals
            var intervals = new TreeSet<MeteredActualInterval>();
            List<String> intervalBlocks = extractArray(block, "intervals");
            for (String ivBlock : intervalBlocks) {
                intervals.add(new DefaultMeteredActualInterval(
                    UUID.fromString(extractString(ivBlock, "id")),
                    Instant.parse(extractString(ivBlock, "intervalStart")),
                    Instant.parse(extractString(ivBlock, "intervalEnd")),
                    extractDecimal(ivBlock, "volume"),
                    extractDecimal(ivBlock, "energy"),
                    extractInt(ivBlock, "version"),
                    null));
            }

            var series = new DefaultMeteredActualVolumeSeries(
                UUID.fromString(extractString(block, "id")),
                new SeriesKey(key),
                extractString(block, "assetId"),
                extractString(block, "meteringPointId"),
                extractLong(block, "versionId"),
                VolumeUnit.valueOf(extractString(block, "volumeUnit")),
                TimeGranularity.valueOf(extractString(block, "granularity")),
                new DeliveryPeriod(start, end, tz),
                qs, ms, mt,
                extractInt(block, "totalExpectedIntervals"),
                extractInt(block, "materializedIntervalCount"),
                txTime, rxTime, intervals);

            result.put(key, series);
        }
        return Map.copyOf(result);
    }

    // --- Minimal JSON parsing (reused patterns from JsonPriceExpressionRepository) ---

    private String readResource() {
        try (var is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) return "{}";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(?:\"([^\"]*)\"|null)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static BigDecimal extractDecimal(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)");
        Matcher m = p.matcher(json);
        return m.find() ? new BigDecimal(m.group(1)) : BigDecimal.ZERO;
    }

    static int extractInt(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+)");
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static long extractLong(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+)");
        Matcher m = p.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
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
