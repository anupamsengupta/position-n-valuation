package com.power.posval.domain.service;

import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.port.event.ChunkEnqueuer;
import com.power.posval.domain.port.event.DomainEventPublisher;

import java.time.YearMonth;

/**
 * Rolling-horizon: materialize M+1 through M+3, enqueue remaining as chunks.
 * V3.0 §4.2: long-tenor trades (PPAs, multi-year bilateral).
 * Pattern #11.
 */
public record RollingHorizonStrategy(
    int horizonMonths,
    ChunkEnqueuer enqueuer
) implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            Object writer,
                            DomainEventPublisher publisher) {
        YearMonth now = YearMonth.now();
        YearMonth through = now.plusMonths(horizonMonths);

        // Near-term: materialize immediately (requires BatchWriter from pv-persistence)
        // Far-dated: enqueue monthly chunks (Kafka messages)
        YearMonth month = through.plusMonths(1);
        YearMonth end = YearMonth.from(series.deliveryPeriod().end());
        while (!month.isAfter(end)) {
            enqueuer.enqueue(series.seriesKey(), month);
            month = month.plusMonths(1);
        }
    }
}
