package com.power.posval.domain.model;

/**
 * Classification of a cashflow by settlement stream.
 * Enables treasury/liquidity modules to route cashflows to the correct ledger account.
 * Pattern #4, S4.2, DA-SET-02.
 */
public enum CashflowType {
    /** Energy delivery settlement amount (ECC net settlement). */
    ENERGY_SETTLEMENT,
    /** Exchange transaction and clearing fee charges. */
    EXCHANGE_FEE,
    /** TSO imbalance settlement amount (reBAP-based). */
    IMBALANCE_SETTLEMENT
}
