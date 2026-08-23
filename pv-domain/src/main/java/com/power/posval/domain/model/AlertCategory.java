package com.power.posval.domain.model;

/**
 * Operational alert category classifying the domain area that raised the alert.
 * Used by OperationalAlertRepository query methods to filter by subsystem.
 * Pattern #4, S4.2, DA-OPS-01.
 */
public enum AlertCategory {
    /** Failures or anomalies during EPEX CSV import. */
    AUCTION_INGESTION,
    /** Missing or inconsistent DA clearing price fixings. */
    DA_CLEARING_PRICES,
    /** Nomination submission issues or deadline breaches. */
    NOMINATION_SCHEDULING,
    /** Imbalance computation failures or TSO data quality issues. */
    IMBALANCE_SETTLEMENT,
    /** Exchange fee schedule gaps or computation errors. */
    EXCHANGE_FEES,
    /** General settlement computation failures. */
    SETTLEMENT
}
