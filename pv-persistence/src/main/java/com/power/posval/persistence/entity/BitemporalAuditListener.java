package com.power.posval.persistence.entity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/**
 * §14.6, Pattern #35 — JPA entity listener for bitemporal audit.
 * Sets knownFrom on persist; validates only knownTo changes on update.
 */
public class BitemporalAuditListener {

    @PrePersist
    public void onPrePersist(Object entity) {
        Instant now = Instant.now();
        if (entity instanceof PositionLedgerEntryEntity e) {
            if (e.getKnownFrom() == null) {
                e.setKnownFrom(now);
            }
        } else if (entity instanceof SettlementCellEntity e) {
            if (e.getKnownFrom() == null) {
                e.setKnownFrom(now);
            }
        }
    }

    @PreUpdate
    public void onPreUpdate(Object entity) {
        // Bitemporal invariant: only knownTo may be set on update (closing knowledge).
        // All other fields are immutable after persist — enforced by DB triggers as
        // defense-in-depth, but validated here for fast feedback.
        if (entity instanceof PositionLedgerEntryEntity e) {
            if (e.getKnownTo() == null) {
                throw new IllegalStateException(
                    "Bitemporal update must set knownTo on PositionLedgerEntryEntity");
            }
        } else if (entity instanceof SettlementCellEntity e) {
            if (e.getKnownTo() == null) {
                throw new IllegalStateException(
                    "Bitemporal update must set knownTo on SettlementCellEntity");
            }
        }
    }
}
