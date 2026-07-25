package com.power.posval.persistence.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimpleJsonCodecTest {

    @Test
    void setToJsonAndBack() {
        Set<String> input = Set.of("EPEX_DA15", "SPREAD_5", "FIXED_85");
        String json = SimpleJsonCodec.setToJson(input);

        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));

        Set<String> roundTripped = SimpleJsonCodec.jsonToStringSet(json);
        assertEquals(input, roundTripped);
    }

    @Test
    void emptySetRoundTrip() {
        assertEquals("[]", SimpleJsonCodec.setToJson(Set.of()));
        assertEquals(Set.of(), SimpleJsonCodec.jsonToStringSet("[]"));
        assertEquals(Set.of(), SimpleJsonCodec.jsonToStringSet(null));
        assertEquals(Set.of(), SimpleJsonCodec.jsonToStringSet(""));
    }

    @Test
    void mapToJsonAndBack() {
        Map<String, Long> input = Map.of("EPEX_DA15", 42L, "EEX_BASE_DE", 7L);
        String json = SimpleJsonCodec.mapToJson(input);

        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"EPEX_DA15\":42"));
        assertTrue(json.contains("\"EEX_BASE_DE\":7"));

        Map<String, Long> roundTripped = SimpleJsonCodec.jsonToStringLongMap(json);
        assertEquals(input, roundTripped);
    }

    @Test
    void emptyMapRoundTrip() {
        assertEquals("{}", SimpleJsonCodec.mapToJson(Map.of()));
        assertEquals(Map.of(), SimpleJsonCodec.jsonToStringLongMap("{}"));
        assertEquals(Map.of(), SimpleJsonCodec.jsonToStringLongMap(null));
        assertEquals(Map.of(), SimpleJsonCodec.jsonToStringLongMap(""));
    }

    @Test
    void singleElementSet() {
        Set<String> input = Set.of("LEAF_A");
        String json = SimpleJsonCodec.setToJson(input);
        assertEquals("[\"LEAF_A\"]", json);
        assertEquals(input, SimpleJsonCodec.jsonToStringSet(json));
    }

    @Test
    void singleElementMap() {
        Map<String, Long> input = Map.of("SERIES_X", 100L);
        String json = SimpleJsonCodec.mapToJson(input);
        assertEquals("{\"SERIES_X\":100}", json);
        assertEquals(input, SimpleJsonCodec.jsonToStringLongMap(json));
    }
}
