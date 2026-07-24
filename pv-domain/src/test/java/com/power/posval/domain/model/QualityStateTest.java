package com.power.posval.domain.model;

import com.power.posval.domain.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QualityStateTest {

    // Allowed transitions
    @Test void effectiveToAmended() { assertTrue(QualityState.EFFECTIVE.canTransitionTo(QualityState.AMENDED)); }
    @Test void currentToSuperseded() { assertTrue(QualityState.CURRENT.canTransitionTo(QualityState.SUPERSEDED)); }
    @Test void provisionalToValidated() { assertTrue(QualityState.PROVISIONAL.canTransitionTo(QualityState.VALIDATED)); }
    @Test void provisionalToEstimated() { assertTrue(QualityState.PROVISIONAL.canTransitionTo(QualityState.ESTIMATED)); }
    @Test void estimatedToValidated() { assertTrue(QualityState.ESTIMATED.canTransitionTo(QualityState.VALIDATED)); }

    // Terminal states block all transitions
    @Test void amendedIsTerminal() {
        for (QualityState target : QualityState.values()) {
            assertFalse(QualityState.AMENDED.canTransitionTo(target));
        }
    }

    @Test void supersededIsTerminal() {
        for (QualityState target : QualityState.values()) {
            assertFalse(QualityState.SUPERSEDED.canTransitionTo(target));
        }
    }

    @Test void validatedIsTerminal() {
        for (QualityState target : QualityState.values()) {
            assertFalse(QualityState.VALIDATED.canTransitionTo(target));
        }
    }

    // Disallowed transitions
    @Test void effectiveToCurrentBlocked() { assertFalse(QualityState.EFFECTIVE.canTransitionTo(QualityState.CURRENT)); }
    @Test void currentToAmendedBlocked() { assertFalse(QualityState.CURRENT.canTransitionTo(QualityState.AMENDED)); }
    @Test void estimatedToEstimatedBlocked() { assertFalse(QualityState.ESTIMATED.canTransitionTo(QualityState.ESTIMATED)); }

    // transitionTo returns target on success
    @Test void transitionToReturnsTarget() {
        assertEquals(QualityState.AMENDED, QualityState.EFFECTIVE.transitionTo(QualityState.AMENDED));
    }

    // transitionTo throws on illegal transition
    @Test void transitionToThrowsOnIllegal() {
        assertThrows(IllegalStateTransitionException.class,
            () -> QualityState.AMENDED.transitionTo(QualityState.EFFECTIVE));
    }

    // Category predicates
    @Test void profileStates() {
        assertTrue(QualityState.EFFECTIVE.isProfileState());
        assertTrue(QualityState.AMENDED.isProfileState());
        assertFalse(QualityState.CURRENT.isProfileState());
        assertFalse(QualityState.PROVISIONAL.isProfileState());
    }

    @Test void forecastStates() {
        assertTrue(QualityState.CURRENT.isForecastState());
        assertTrue(QualityState.SUPERSEDED.isForecastState());
        assertFalse(QualityState.EFFECTIVE.isForecastState());
        assertFalse(QualityState.PROVISIONAL.isForecastState());
    }

    @Test void meteredStates() {
        assertTrue(QualityState.PROVISIONAL.isMeteredState());
        assertTrue(QualityState.VALIDATED.isMeteredState());
        assertTrue(QualityState.ESTIMATED.isMeteredState());
        assertFalse(QualityState.EFFECTIVE.isMeteredState());
        assertFalse(QualityState.CURRENT.isMeteredState());
    }
}
