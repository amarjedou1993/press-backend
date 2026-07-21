package com.presscard.press_accreditation.session;

/** Thrown when a session id doesn't exist → mapped to 404. */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(Long id) {
        super("Session not found: " + id);
    }
}
