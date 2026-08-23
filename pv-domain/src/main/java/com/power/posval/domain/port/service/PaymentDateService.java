package com.power.posval.domain.port.service;

import java.time.LocalDate;

/**
 * Service port for settlement payment date computation (DA-SET-02).
 *
 * <p>The default convention for EPEX DA ECC settlement is D+2 TARGET2 business days,
 * where D is the delivery day. The {@code TARGET2CalendarRepository} provides the
 * banking day calendar for the roll.
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface PaymentDateService {

    /**
     * Compute the payment date for a given delivery day using the TARGET2 calendar.
     *
     * <p>Delegates to {@code TARGET2CalendarRepository.nthBusinessDayAfter(deliveryDay, n)}.
     * For DA ECC settlement {@code businessDaysAfter = 2}.
     *
     * @param deliveryDay      the CET-interpreted delivery date
     * @param businessDaysAfter the number of TARGET2 business days to advance;
     *                          must be &gt;= 1; default for DA is {@code 2}
     * @return the payment date; never null
     * @throws IllegalArgumentException if {@code businessDaysAfter} &lt; 1
     */
    LocalDate computePaymentDate(LocalDate deliveryDay, int businessDaysAfter);
}
