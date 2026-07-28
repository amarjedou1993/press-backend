package com.presscard.press_accreditation.error;

/** Acting on a dossier claimed by someone else, or by nobody. → 409 */
public class NotYourClaimException extends RuntimeException {
    public NotYourClaimException(String message) {
        super(message);
    }
}
