package com.power.posval.domain.model;

/**
 * Block order type for DA auction decomposition.
 * BASELOAD, PEAK, and OFF_PEAK are standard EPEX block types with
 * fixed CET-window definitions in BlockDefinition reference data.
 * CUSTOM covers exchange-specific or user-defined block structures.
 * Pattern #4, S4.2, DA-VOL-02.
 */
public enum BlockType {
    /** 00:00–24:00 CET, all days. */
    BASELOAD,
    /** 08:00–20:00 CET, Monday–Friday excluding public holidays. */
    PEAK,
    /** All intervals not covered by PEAK; includes weekends and holidays. */
    OFF_PEAK,
    /** Exchange-specific or bespoke block structure defined in BlockDefinition. */
    CUSTOM
}
