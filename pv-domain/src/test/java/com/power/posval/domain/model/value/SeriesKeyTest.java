package com.power.posval.domain.model.value;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeriesKeyTest {

    @Test
    void constructsWithValidValue() {
        var key = new SeriesKey("FCST-WP-NORDSEE");
        assertEquals("FCST-WP-NORDSEE", key.value());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> new SeriesKey(null));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new SeriesKey("  "));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new SeriesKey(""));
    }

    @Test
    void ofFactory() {
        var key = SeriesKey.of("FCST", "WP123");
        assertEquals("FCST-WP123", key.value());
    }

    @Test
    void toStringReturnsValue() {
        var key = new SeriesKey("VS-T5500-1");
        assertEquals("VS-T5500-1", key.toString());
    }

    @Test
    void equalityByValue() {
        assertEquals(new SeriesKey("ABC"), new SeriesKey("ABC"));
        assertNotEquals(new SeriesKey("ABC"), new SeriesKey("DEF"));
    }
}
