package com.presscard.press_accreditation.error;

/** Two reviewers claimed the same dossier at the same instant; one lost the race. → 409 */
public class AlreadyClaimedException extends RuntimeException {
    public AlreadyClaimedException(String message) {
        super(message);
    }
}
