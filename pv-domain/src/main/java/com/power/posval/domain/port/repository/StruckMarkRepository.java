package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.StruckMark;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for struck mark persistence (S5c).
 * FR-077: immutable, append-only.
 * FR-078: month-bucket grain.
 */
public interface StruckMarkRepository {

    void save(StruckMark mark);

    Optional<StruckMark> findLatest(String tenantId, UUID positionId,
                                     YearMonth deliveryMonth);

    List<StruckMark> findByStrikeDate(String tenantId, LocalDate strikeDate);
}
