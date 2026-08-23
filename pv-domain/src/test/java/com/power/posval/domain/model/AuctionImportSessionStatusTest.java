package com.power.posval.domain.model;

import com.power.posval.domain.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * State-machine tests for {@link AuctionImportSession}.
 *
 * <p>Covers the full valid transition chain and all failure transitions
 * defined in Pattern #16, S4.3, DA-VOL-01.
 */
class AuctionImportSessionStatusTest {

    // ---------------------------------------------------------------------------
    // Valid forward transitions
    // ---------------------------------------------------------------------------

    @Test
    void pendingToValidating_isAllowed() {
        AuctionImportSession session = newSession(AuctionImportStatus.PENDING);
        assertDoesNotThrow(() -> session.transitionTo(AuctionImportStatus.VALIDATING));
        assertEquals(AuctionImportStatus.VALIDATING, session.status());
    }

    @Test
    void validatingToValidated_isAllowed() {
        AuctionImportSession session = newSession(AuctionImportStatus.VALIDATING);
        assertDoesNotThrow(() -> session.transitionTo(AuctionImportStatus.VALIDATED));
        assertEquals(AuctionImportStatus.VALIDATED, session.status());
    }

    @Test
    void validatedToImporting_isAllowed() {
        AuctionImportSession session = newSession(AuctionImportStatus.VALIDATED);
        assertDoesNotThrow(() -> session.transitionTo(AuctionImportStatus.IMPORTING));
        assertEquals(AuctionImportStatus.IMPORTING, session.status());
    }

    @Test
    void importingToImported_isAllowed() {
        AuctionImportSession session = newSession(AuctionImportStatus.IMPORTING);
        assertDoesNotThrow(() -> session.transitionTo(AuctionImportStatus.IMPORTED));
        assertEquals(AuctionImportStatus.IMPORTED, session.status());
    }

    // ---------------------------------------------------------------------------
    // Valid failure transitions
    // ---------------------------------------------------------------------------

    @Test
    void validatingToValidationFailed_isAllowed() {
        AuctionImportSession session = newSession(AuctionImportStatus.VALIDATING);
        assertDoesNotThrow(() -> session.transitionTo(AuctionImportStatus.VALIDATION_FAILED));
        assertEquals(AuctionImportStatus.VALIDATION_FAILED, session.status());
    }

    @Test
    void importingToImportFailed_isAllowed() {
        AuctionImportSession session = newSession(AuctionImportStatus.IMPORTING);
        assertDoesNotThrow(() -> session.transitionTo(AuctionImportStatus.IMPORT_FAILED));
        assertEquals(AuctionImportStatus.IMPORT_FAILED, session.status());
    }

    // ---------------------------------------------------------------------------
    // Terminal states block all transitions
    // ---------------------------------------------------------------------------

    @Test
    void importedIsTerminal_allTransitionsBlocked() {
        for (AuctionImportStatus target : AuctionImportStatus.values()) {
            AuctionImportSession session = newSession(AuctionImportStatus.IMPORTED);
            assertThrows(IllegalStateTransitionException.class,
                () -> session.transitionTo(target),
                "Expected IMPORTED -> " + target + " to throw");
        }
    }

    @Test
    void validationFailedIsTerminal_allTransitionsBlocked() {
        for (AuctionImportStatus target : AuctionImportStatus.values()) {
            AuctionImportSession session = newSession(AuctionImportStatus.VALIDATION_FAILED);
            assertThrows(IllegalStateTransitionException.class,
                () -> session.transitionTo(target),
                "Expected VALIDATION_FAILED -> " + target + " to throw");
        }
    }

    @Test
    void importFailedIsTerminal_allTransitionsBlocked() {
        for (AuctionImportStatus target : AuctionImportStatus.values()) {
            AuctionImportSession session = newSession(AuctionImportStatus.IMPORT_FAILED);
            assertThrows(IllegalStateTransitionException.class,
                () -> session.transitionTo(target),
                "Expected IMPORT_FAILED -> " + target + " to throw");
        }
    }

    // ---------------------------------------------------------------------------
    // Specific invalid transitions
    // ---------------------------------------------------------------------------

    @Test
    void importedToPending_throws() {
        AuctionImportSession session = newSession(AuctionImportStatus.IMPORTED);
        assertThrows(IllegalStateTransitionException.class,
            () -> session.transitionTo(AuctionImportStatus.PENDING));
    }

    @Test
    void pendingToImported_throws() {
        AuctionImportSession session = newSession(AuctionImportStatus.PENDING);
        assertThrows(IllegalStateTransitionException.class,
            () -> session.transitionTo(AuctionImportStatus.IMPORTED));
    }

    @Test
    void validatingToImporting_throws() {
        AuctionImportSession session = newSession(AuctionImportStatus.VALIDATING);
        assertThrows(IllegalStateTransitionException.class,
            () -> session.transitionTo(AuctionImportStatus.IMPORTING));
    }

    @Test
    void nullTarget_throwsNpe() {
        AuctionImportSession session = newSession(AuctionImportStatus.PENDING);
        assertThrows(NullPointerException.class, () -> session.transitionTo(null));
    }

    // ---------------------------------------------------------------------------
    // Full happy-path chain
    // ---------------------------------------------------------------------------

    @Test
    void fullHappyPathChain_transitionsCorrectly() {
        AuctionImportSession session = newSession(AuctionImportStatus.PENDING);
        session.transitionTo(AuctionImportStatus.VALIDATING);
        session.transitionTo(AuctionImportStatus.VALIDATED);
        session.transitionTo(AuctionImportStatus.IMPORTING);
        session.transitionTo(AuctionImportStatus.IMPORTED);
        assertEquals(AuctionImportStatus.IMPORTED, session.status());
    }

    // ---------------------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------------------

    private static AuctionImportSession newSession(AuctionImportStatus status) {
        return AuctionImportSession.builder()
            .sessionId(UUID.randomUUID())
            .tenantId("TN_0042")
            .exchange("EPEX_SPOT")
            .biddingZone("DE_LU")
            .deliveryDay(LocalDate.of(2026, 9, 16))
            .importTimestamp(Instant.now())
            .status(status)
            .exchangeReportedTotalMwh(new BigDecimal("12450.00"))
            .intervalCount(96)
            .fileReference("EPEX_SPOT_DE_LU_20260916.csv")
            .createdAt(Instant.now())
            .build();
    }
}
