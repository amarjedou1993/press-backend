package com.presscard.press_accreditation.error;

/** The application is in a state the candidate may no longer edit. → 409 */
public class ApplicationNotEditableException extends RuntimeException {
    public ApplicationNotEditableException(String message) {
        super(message);
    }
}
