package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.expression.PriceExpression;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for loading and persisting price expression trees by ID.
 * Implementations may be backed by a database, JSON file, or remote service.
 *
 * <p>The {@link #save} method supports the DA auction import pipeline, which creates a
 * {@code ConstantLeaf} price expression for every executed interval contract
 * (DA-PRC-01, S8.1 step 5b, D-2). Fixed prices are stored as degenerate expression trees
 * so the settlement pipeline can evaluate them uniformly.
 *
 * <p>FR-048h, Pattern #18.
 */
public interface PriceExpressionRepository {

    Optional<PriceExpression> findById(UUID id);

    /**
     * Persist a price expression tree. Idempotent on the same {@code id}:
     * if an expression with the same ID already exists it must not be overwritten
     * (expressions are immutable per D-2 and S10.5).
     *
     * @param id         the UUID that will be used as the {@code priceExpressionId}
     *                   on the {@code PositionLedgerEntry}; never null
     * @param expression the expression tree root to persist; never null
     */
    void save(UUID id, PriceExpression expression);
}
