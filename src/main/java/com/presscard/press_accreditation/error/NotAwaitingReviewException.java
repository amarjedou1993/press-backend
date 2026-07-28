package com.presscard.press_accreditation.error;

/** The dossier is not in a state the commission may act on. → 409 */
public class NotAwaitingReviewException extends RuntimeException {
    public NotAwaitingReviewException(String message) {
        super(message);
    }
}
