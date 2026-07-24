package com.power.posval.domain.model;

import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

/**
 * Concrete implementation of VolumeSeries.
 * Immutable after construction via Builder.
 * Pattern #2, #6, FR-050, D-11, S3.
 */
public final class DefaultVolumeSeries implements VolumeSeries {

    private final UUID id;
    private final SeriesKey seriesKey;
    private final SeriesType seriesType;
    private final String assetId;
    private final String tradeLegId;
    private final long versionId;
    private final VolumeUnit volumeUnit;
    private final TimeGranularity granularity;
    private final DeliveryPeriod deliveryPeriod;
    private final QualityState qualityState;
    private final MaterializationStatus materializationStatus;
    private final YearMonth materializedThrough;
    private final int totalExpectedIntervals;
    private final int materializedIntervalCount;
    private final Instant transactionTime;
    private final Instant validTime;
    private final SequencedSet<VolumeInterval> intervals;

    private DefaultVolumeSeries(Builder b) {
        this.id = b.id;
        this.seriesKey = b.seriesKey;
        this.seriesType = b.seriesType;
        this.assetId = b.assetId;
        this.tradeLegId = b.tradeLegId;
        this.versionId = b.versionId;
        this.volumeUnit = b.volumeUnit;
        this.granularity = b.granularity;
        this.deliveryPeriod = b.deliveryPeriod;
        this.qualityState = b.qualityState;
        this.materializationStatus = b.materializationStatus;
        this.materializedThrough = b.materializedThrough;
        this.totalExpectedIntervals = b.totalExpectedIntervals;
        this.materializedIntervalCount = b.materializedIntervalCount;
        this.transactionTime = b.transactionTime;
        this.validTime = b.validTime;
        this.intervals = Collections.unmodifiableSequencedSet(
            new TreeSet<>(b.intervals));
    }

    public static Builder builder() { return new Builder(); }

    @Override public UUID id() { return id; }
    @Override public SeriesKey seriesKey() { return seriesKey; }
    @Override public SeriesType seriesType() { return seriesType; }
    @Override public String assetId() { return assetId; }
    @Override public String tradeLegId() { return tradeLegId; }
    @Override public long versionId() { return versionId; }
    @Override public VolumeUnit volumeUnit() { return volumeUnit; }
    @Override public TimeGranularity granularity() { return granularity; }
    @Override public DeliveryPeriod deliveryPeriod() { return deliveryPeriod; }
    @Override public QualityState qualityState() { return qualityState; }
    @Override public MaterializationStatus materializationStatus() { return materializationStatus; }
    @Override public YearMonth materializedThrough() { return materializedThrough; }
    @Override public int totalExpectedIntervals() { return totalExpectedIntervals; }
    @Override public int materializedIntervalCount() { return materializedIntervalCount; }
    @Override public Instant transactionTime() { return transactionTime; }
    @Override public Instant validTime() { return validTime; }
    @Override public SequencedSet<VolumeInterval> intervals() { return intervals; }

    public static final class Builder {
        private UUID id;
        private SeriesKey seriesKey;
        private SeriesType seriesType;
        private String assetId;
        private String tradeLegId;
        private long versionId;
        private VolumeUnit volumeUnit;
        private TimeGranularity granularity;
        private DeliveryPeriod deliveryPeriod;
        private QualityState qualityState = QualityState.CURRENT;
        private MaterializationStatus materializationStatus = MaterializationStatus.PENDING;
        private YearMonth materializedThrough;
        private int totalExpectedIntervals;
        private int materializedIntervalCount;
        private Instant transactionTime;
        private Instant validTime;
        private List<VolumeInterval> intervals = List.of();

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder seriesKey(SeriesKey v) { this.seriesKey = v; return this; }
        public Builder seriesType(SeriesType v) { this.seriesType = v; return this; }
        public Builder assetId(String v) { this.assetId = v; return this; }
        public Builder tradeLegId(String v) { this.tradeLegId = v; return this; }
        public Builder versionId(long v) { this.versionId = v; return this; }
        public Builder volumeUnit(VolumeUnit v) { this.volumeUnit = v; return this; }
        public Builder granularity(TimeGranularity v) { this.granularity = v; return this; }
        public Builder deliveryPeriod(DeliveryPeriod v) { this.deliveryPeriod = v; return this; }
        public Builder qualityState(QualityState v) { this.qualityState = v; return this; }
        public Builder materializationStatus(MaterializationStatus v) { this.materializationStatus = v; return this; }
        public Builder materializedThrough(YearMonth v) { this.materializedThrough = v; return this; }
        public Builder totalExpectedIntervals(int v) { this.totalExpectedIntervals = v; return this; }
        public Builder materializedIntervalCount(int v) { this.materializedIntervalCount = v; return this; }
        public Builder transactionTime(Instant v) { this.transactionTime = v; return this; }
        public Builder validTime(Instant v) { this.validTime = v; return this; }
        public Builder intervals(List<VolumeInterval> v) { this.intervals = v; return this; }

        public DefaultVolumeSeries build() {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(seriesKey, "seriesKey");
            Objects.requireNonNull(seriesType, "seriesType");
            Objects.requireNonNull(volumeUnit, "volumeUnit");
            Objects.requireNonNull(granularity, "granularity");
            Objects.requireNonNull(deliveryPeriod, "deliveryPeriod");
            Objects.requireNonNull(transactionTime, "transactionTime");
            if (seriesType == SeriesType.FORECAST && assetId == null) {
                throw new IllegalStateException("FORECAST series requires assetId");
            }
            if (seriesType == SeriesType.PROFILE && tradeLegId == null) {
                throw new IllegalStateException("PROFILE series requires tradeLegId");
            }
            return new DefaultVolumeSeries(this);
        }
    }
}
