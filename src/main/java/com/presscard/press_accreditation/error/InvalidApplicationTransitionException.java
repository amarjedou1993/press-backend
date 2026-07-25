package com.presscard.press_accreditation.error;

/** A status change the state machine does not allow. → 409 */
public class InvalidApplicationTransitionException extends RuntimeException {
    public InvalidApplicationTransitionException(String message) {
        super(message);
    }
}
