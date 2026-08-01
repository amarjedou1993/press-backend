package com.presscard.press_accreditation.error;

/**
 * An objection was attempted where the right does not apply: already
 * exercised, the decision is not a rejection, or the reclamation window is
 * closed. → 409
 */
public class ObjectionNotAllowedException extends RuntimeException {
    public ObjectionNotAllowedException(String message) { super(message); }
}