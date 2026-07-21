package com.presscard.press_accreditation.error;

/** Illegal session phase move (e.g. advancing a CLOSED session) → 409. */
public class InvalidPhaseTransitionException extends RuntimeException {
    public InvalidPhaseTransitionException(String message) {
        super(message);
    }
}
