package com.presscard.press_accreditation.error;

/** The session is not accepting candidatures. → 409 */
public class SessionNotOpenException extends RuntimeException {
    public SessionNotOpenException(String message) {
        super(message);
    }
}
