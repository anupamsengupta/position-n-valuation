package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.expression.PriceExpression;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for loading price expression trees by ID.
 * Implementations may be backed by a database, JSON file, or remote service.
 * FR-048h, Pattern #18.
 */
public interface PriceExpressionRepository {

    Optional<PriceExpression> findById(UUID id);
}
