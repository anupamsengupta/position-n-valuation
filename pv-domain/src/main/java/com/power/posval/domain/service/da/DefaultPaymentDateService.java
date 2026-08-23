package com.power.posval.domain.service.da;

import com.power.posval.domain.port.repository.TARGET2CalendarRepository;
import com.power.posval.domain.port.service.PaymentDateService;

import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Default implementation of {@link PaymentDateService}.
 *
 * <p>Delegates entirely to {@link TARGET2CalendarRepository#nthBusinessDayAfter} to
 * compute the ECC D+2 TARGET2 settlement date for DA exchange spot contracts (DA-SET-02).
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, DA-SET-02.
 */
public class DefaultPaymentDateService implements PaymentDateService {

    private final TARGET2CalendarRepository target2CalendarRepository;

    @Inject
    public DefaultPaymentDateService(TARGET2CalendarRepository target2CalendarRepository) {
        this.target2CalendarRepository =
            Objects.requireNonNull(target2CalendarRepository, "target2CalendarRepository");
    }

    /**
     * Compute the payment date by advancing {@code businessDaysAfter} TARGET2 business
     * days from {@code deliveryDay}. For DA ECC settlement the caller passes {@code 2}.
     *
     * <p>DA-SET-02.
     *
     * @param deliveryDay       CET-interpreted delivery date
     * @param businessDaysAfter number of TARGET2 business days to advance; must be >= 1
     * @return the resulting TARGET2 payment date; never null
     * @throws IllegalArgumentException if {@code businessDaysAfter} < 1
     */
    @Override
    public LocalDate computePaymentDate(LocalDate deliveryDay, int businessDaysAfter) {
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        if (businessDaysAfter < 1) {
            throw new IllegalArgumentException(
                "businessDaysAfter must be >= 1, was: " + businessDaysAfter);
        }
        return target2CalendarRepository.nthBusinessDayAfter(deliveryDay, businessDaysAfter);
    }
}
