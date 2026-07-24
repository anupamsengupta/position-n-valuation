package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;

import java.time.Instant;

/**
 * Composable query specification for volume series lookups.
 * Pattern #19: functional interface, composed via .and()/.or().
 * The pv-persistence adapter translates these to JPA CriteriaQuery predicates.
 */
@FunctionalInterface
public interface VolumeSeriesSpec {

    /**
     * Apply this specification to a CriteriaBuilder query.
     * @return predicate representing this filter condition
     */
    jakarta.persistence.criteria.Predicate toPredicate(
        jakarta.persistence.criteria.Root<?> root,
        jakarta.persistence.criteria.CriteriaQuery<?> query,
        jakarta.persistence.criteria.CriteriaBuilder cb);

    /** Compose with AND. */
    default VolumeSeriesSpec and(VolumeSeriesSpec other) {
        return (root, query, cb) -> cb.and(
            this.toPredicate(root, query, cb),
            other.toPredicate(root, query, cb));
    }

    /** Compose with OR. */
    default VolumeSeriesSpec or(VolumeSeriesSpec other) {
        return (root, query, cb) -> cb.or(
            this.toPredicate(root, query, cb),
            other.toPredicate(root, query, cb));
    }

    static VolumeSeriesSpec byAsset(String assetId) {
        return (root, query, cb) -> cb.equal(root.get("assetId"), assetId);
    }

    static VolumeSeriesSpec byTradeLeg(String tradeLegId) {
        return (root, query, cb) -> cb.equal(root.get("tradeLegId"), tradeLegId);
    }

    static VolumeSeriesSpec currentVersionOnly() {
        return (root, query, cb) -> root.get("qualityState")
            .in(QualityState.CURRENT, QualityState.EFFECTIVE);
    }

    static VolumeSeriesSpec bySeriesType(SeriesType type) {
        return (root, query, cb) -> cb.equal(root.get("seriesType"), type);
    }

    static VolumeSeriesSpec withinDeliveryRange(Instant start, Instant end) {
        return (root, query, cb) -> cb.and(
            cb.lessThan(root.get("deliveryStart"), end),
            cb.greaterThanOrEqualTo(root.get("deliveryEnd"), start));
    }
}
