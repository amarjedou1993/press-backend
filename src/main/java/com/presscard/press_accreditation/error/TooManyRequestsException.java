package com.presscard.press_accreditation.error;

/** Too many token requests for one account in an hour. → 429 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
