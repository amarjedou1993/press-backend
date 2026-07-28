package com.presscard.press_accreditation.error;

/** A decision already exists for this round. → 409 */
public class AlreadyDecidedException extends RuntimeException {
    public AlreadyDecidedException(String message) {
        super(message);
    }
}
