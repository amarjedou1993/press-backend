package com.presscard.press_accreditation.error;

/** One application per candidate per session, and only one submission. → 409 */
public class ApplicationAlreadySubmittedException extends RuntimeException {
    public ApplicationAlreadySubmittedException(String message) {
        super(message);
    }
}
