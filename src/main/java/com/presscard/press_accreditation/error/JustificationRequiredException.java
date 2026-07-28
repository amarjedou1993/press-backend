package com.presscard.press_accreditation.error;

/** A decision that must be explained was submitted without an explanation. → 400 */
public class JustificationRequiredException extends RuntimeException {
    public JustificationRequiredException(String message) {
        super(message);
    }
}
