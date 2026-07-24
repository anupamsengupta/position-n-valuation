package com.power.posval.domain.exception;

import com.power.posval.domain.model.QualityState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IllegalStateTransitionExceptionTest {

    @Test
    void messageContainsBothStates() {
        var ex = new IllegalStateTransitionException(QualityState.AMENDED, QualityState.EFFECTIVE);
        assertTrue(ex.getMessage().contains("AMENDED"));
        assertTrue(ex.getMessage().contains("EFFECTIVE"));
    }

    @Test
    void fromAndToAccessors() {
        var ex = new IllegalStateTransitionException(QualityState.CURRENT, QualityState.EFFECTIVE);
        assertEquals("CURRENT", ex.fromState());
        assertEquals("EFFECTIVE", ex.toState());
    }

    @Test
    void isRuntimeException() {
        var ex = new IllegalStateTransitionException(QualityState.VALIDATED, QualityState.PROVISIONAL);
        assertInstanceOf(RuntimeException.class, ex);
    }
}
