package com.power.posval.domain.model;

/** Pattern #4, V3.0 §3.2.4, S3. */
public enum SeriesType {
    /** Per asset (shared). Weather-model-sourced, frequently updated. */
    FORECAST,
    /** Per trade-leg (dedicated). Contractual, immutable after capture. */
    PROFILE
}
