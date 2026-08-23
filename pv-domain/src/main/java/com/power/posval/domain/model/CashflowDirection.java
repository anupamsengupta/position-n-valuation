package com.power.posval.domain.model;

/**
 * Direction of a cashflow from the tenant's perspective.
 * PAY = cash outflow; RECEIVE = cash inflow.
 * Pattern #4, S4.2, DA-SET-02.
 */
public enum CashflowDirection {
    /** Cash leaves the tenant's account (e.g. exchange fee, imbalance cost). */
    PAY,
    /** Cash enters the tenant's account (e.g. energy settlement credit). */
    RECEIVE
}
