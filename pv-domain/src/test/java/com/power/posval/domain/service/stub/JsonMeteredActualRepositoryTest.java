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
    void loadMeteredActualSeries() {
        var seriesOpt = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-WP-NORDSEE"));
        assertTrue(seriesOpt.isPresent());

        MeteredActualVolumeSeries series = seriesOpt.get();
        assertEquals("ASSET-WP-NORDSEE-01", series.assetId());
        assertEquals("EIC-W-DE000NORDSEE", series.meteringPointId());
        assertEquals(VolumeUnit.MW_CAPACITY, series.volumeUnit());
        assertEquals(QualityState.VALIDATED, series.qualityState());
        assertEquals(4, series.intervals().size());
    }

    @Test
    void intervalsHaveCorrectValues() {
        var series = repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("MTR-WP-NORDSEE")).orElseThrow();

        var firstInterval = series.intervals().getFirst();
        assertEquals(new BigDecimal("42.5"), firstInterval.volume());
        assertEquals(new BigDecimal("10.625"), firstInterval.energy());
        assertEquals(1, firstInterval.version());
    }

    @Test
    void returnsEmptyForUnknownKey() {
        assertTrue(repo.findCurrentBySeriesKey("any-tenant",
            new SeriesKey("NONEXISTENT")).isEmpty());
    }
}
