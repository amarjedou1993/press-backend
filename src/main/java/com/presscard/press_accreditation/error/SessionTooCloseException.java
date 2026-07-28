package com.presscard.press_accreditation.error;

public class SessionTooCloseException extends RuntimeException {
    public SessionTooCloseException(String message) {
        super(message);
    }
}
