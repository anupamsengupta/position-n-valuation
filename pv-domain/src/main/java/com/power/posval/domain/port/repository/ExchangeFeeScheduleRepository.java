package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.ExchangeFeeSchedule;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Port interface for {@link ExchangeFeeSchedule} persistence.
 *
 * <p>Fee schedules are effective-dated: a schedule is applicable when
 * {@code effectiveFrom <= deliveryDate AND (effectiveTo IS NULL OR effectiveTo > deliveryDate)}.
 * Two overloads of {@link #findEffective} are provided — one for the default-tier lookup
 * and one for the member-tier-specific lookup (DA-SET-03).
 *
 * <p>Pattern #18 (Repository Port + Adapter), S5.1.
 */
public interface ExchangeFeeScheduleRepository {

    /**
     * Find the effective fee schedule for the given exchange, fee type, and delivery date,
     * for the default member tier (i.e. {@code memberTier IS NULL}).
     *
     * @param tenantId     tenant identifier (D-14, Pattern #32)
     * @param exchange     exchange code, e.g. {@code "EPEX_SPOT"}
     * @param feeType      fee type code, e.g. {@code "TRADING"} or {@code "CLEARING"}
     * @param deliveryDate the date for which to resolve the effective schedule
     * @return the effective schedule for the default tier, if one exists
     */
    Optional<ExchangeFeeSchedule> findEffective(String tenantId, String exchange,
                                                 String feeType, LocalDate deliveryDate);

    /**
     * Find the effective fee schedule for the given exchange, fee type, member tier,
     * and delivery date.
     *
     * <p>When {@code memberTier} is {@code null} this overload behaves identically to
     * {@link #findEffective(String, String, String, LocalDate)}.
     *
     * @param tenantId     tenant identifier (D-14, Pattern #32)
     * @param exchange     exchange code, e.g. {@code "EPEX_SPOT"}
     * @param feeType      fee type code, e.g. {@code "TRADING"} or {@code "CLEARING"}
     * @param memberTier   member tier code, or {@code null} for the default tier
     * @param deliveryDate the date for which to resolve the effective schedule
     * @return the effective schedule for the given tier, if one exists
     */
    Optional<ExchangeFeeSchedule> findEffective(String tenantId, String exchange,
                                                 String feeType, String memberTier,
                                                 LocalDate deliveryDate);

    /**
     * Persist a new fee schedule entry. Replaces any existing schedule by creating a
     * new row; it is the caller's responsibility to close the previous schedule's
     * {@code effectiveTo} before or during the same transaction.
     *
     * @param schedule the schedule to persist; must not be null
     */
    void save(ExchangeFeeSchedule schedule);
}
