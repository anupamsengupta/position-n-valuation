package com.power.posval.domain.service.stub;

import com.power.posval.domain.model.MeteredActualVolumeSeries;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.SeriesKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class JsonMeteredActualRepositoryTest {

    private static JsonMeteredActualRepository repo;

    @BeforeAll
    static void setUp() {
        repo = new JsonMeteredActualRepository();
    }

    @Test
    void loadWindParkMeteredActualSeries() {
        var seriesOpt = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-WP-NORDSEE"));
        assertTrue(seriesOpt.isPresent());

        MeteredActualVolumeSeries series = seriesOpt.get();
        assertEquals("ASSET-WP-NORDSEE-01", series.assetId());
        assertEquals("EIC-W-DE000NORDSEE", series.meteringPointId());
        assertEquals(VolumeUnit.MW_CAPACITY, series.volumeUnit());
        assertEquals(QualityState.VALIDATED, series.qualityState());
        // 7 days x 96 intervals = 672 intervals
        assertEquals(672, series.intervals().size());
    }

    @Test
    void windIntervalsHaveRealisticValues() {
        var series = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-WP-NORDSEE")).orElseThrow();

        var firstInterval = series.intervals().getFirst();
        // Wind park at midnight — typically moderate-to-high generation
        assertEquals(new BigDecimal("45.8"), firstInterval.volume());
        assertEquals(new BigDecimal("11.45"), firstInterval.energy());
        assertEquals(1, firstInterval.version());
        // All values should be within wind park capacity (0-80 MW)
        series.intervals().forEach(iv -> {
            assertTrue(iv.volume().compareTo(BigDecimal.ZERO) >= 0,
                "Wind volume should be non-negative");
            assertTrue(iv.volume().compareTo(new BigDecimal("80.1")) < 0,
                "Wind volume should be within capacity");
        });
    }

    @Test
    void loadSolarFarmMeteredActualSeries() {
        var seriesOpt = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-SP-BAYERN"));
        assertTrue(seriesOpt.isPresent());

        MeteredActualVolumeSeries series = seriesOpt.get();
        assertEquals("ASSET-SP-BAYERN-01", series.assetId());
        assertEquals(QualityState.PROVISIONAL, series.qualityState());
        assertEquals(672, series.intervals().size());
    }

    @Test
    void solarHasZeroGenerationAtNight() {
        var series = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-SP-BAYERN")).orElseThrow();

        // Check midnight interval — solar should produce zero
        var midnight = series.intervals().getFirst();
        assertEquals(0, BigDecimal.ZERO.compareTo(midnight.volume()),
            "Solar should produce zero at midnight");
    }

    @Test
    void correctedWindReadingsExist() {
        // Version 2 corrections for day 1
        var seriesOpt = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-WP-NORDSEE-V2"));
        assertTrue(seriesOpt.isPresent());

        MeteredActualVolumeSeries corrected = seriesOpt.get();
        assertEquals(2, corrected.versionId());
        assertEquals(96, corrected.intervals().size(), "Day 1 corrections = 96 intervals");
    }

    @Test
    void returnsEmptyForUnknownKey() {
        assertTrue(repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("NONEXISTENT")).isEmpty());
    }
}
