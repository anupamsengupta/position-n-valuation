package com.power.posval.domain.benchmark;

import com.power.posval.domain.model.*;
import com.power.posval.domain.model.value.*;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import org.openjdk.jmh.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark suite for domain operations. §18a.1, TR-043.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class DomainBenchmarkSuite {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private DeliveryPeriod twelveMon;
    private NumericPrecision np;

    @Setup
    public void setup() {
        twelveMon = new DeliveryPeriod(
            ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, CET), CET);
        np = new DefaultNumericPrecision();
    }

    /** Target p95: < 1 µs */
    @Benchmark
    public boolean qualityStateTransition() {
        return QualityState.CURRENT.canTransitionTo(QualityState.SUPERSEDED)
            && QualityState.PROVISIONAL.canTransitionTo(QualityState.VALIDATED)
            && !QualityState.SUPERSEDED.canTransitionTo(QualityState.CURRENT);
    }

    /** Target p95: < 10 µs */
    @Benchmark
    public List<DeliveryRange> deliveryPeriodToMonthBlocks() {
        return twelveMon.toMonthBlocks();
    }

    /** Target p95: < 5 µs */
    @Benchmark
    public PositionLedgerEntry positionLedgerEntryBuilder() {
        return PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-7788")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(
                java.time.YearMonth.of(2025, 3), CET))
            .quantity(new BigDecimal("30.00"))
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .portfolioId("PORTFOLIO-1")
            .deliveryPointId("DE_LU")
            .originType("BILATERAL_TRADE")
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .status("ACTIVE")
            .build();
    }

    /** Target p95: < 10 µs */
    @Benchmark
    public BigDecimal volumeUnitToEnergy() {
        return VolumeUnit.MW_CAPACITY.toEnergy(
            new BigDecimal("100.0"),
            java.time.Duration.ofHours(1),
            np);
    }

    /** Target p95: < 5 µs */
    @Benchmark
    public DefaultVolumeSeries volumeSeriesBuilder() {
        return DefaultVolumeSeries.builder()
            .id(UUID.randomUUID())
            .seriesKey(new SeriesKey("VS-BENCH"))
            .seriesType(SeriesType.PROFILE)
            .tradeLegId("LEG-1")
            .versionId(1L)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .granularity(TimeGranularity.HOURLY)
            .deliveryPeriod(new DeliveryPeriod(
                ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
                ZonedDateTime.of(2025, 4, 1, 0, 0, 0, 0, CET), CET))
            .transactionTime(Instant.now())
            .build();
    }

    /** Target p95: < 5 µs */
    @Benchmark
    public VolumeReference volumeReferenceBuilder() {
        return VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId("LEG-1")
            .tradeId("T-7788")
            .assetId("WP-NORDSEE")
            .multiplier(new BigDecimal("0.3"))
            .volumeSeriesKey(new SeriesKey("FCST-WP-NORDSEE"))
            .effectiveFrom(ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, CET))
            .effectiveTo(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, CET))
            .build();
    }

    /** Target p95: < 1 µs */
    @Benchmark
    public BigDecimal numericPrecisionRound() {
        return np.round(
            new BigDecimal("85.123456789012345"),
            NumericPrecision.Domain.PRICE);
    }
}
