package com.power.posval.domain.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Result of evaluating a PriceExpression tree for one interval.
 * FR-045: (value, active_leaves, input_version_set).
 */
public record PriceResolution(
    BigDecimal value,
    Set<String> activeLeaves,
    Map<String, Long> inputVersionSet
) {}
