package com.power.posval.domain.port.event;

import com.power.posval.domain.model.value.SeriesKey;

import java.time.YearMonth;

/**
 * Port for enqueuing chunk materialization requests (V3.0 §4.3).
 * Each chunk is an independent Kafka message.
 * Pattern #11, FR-056, S3.
 */
public interface ChunkEnqueuer {

    /** Enqueue a chunk for deferred materialization. */
    void enqueue(SeriesKey seriesKey, YearMonth month);
}
