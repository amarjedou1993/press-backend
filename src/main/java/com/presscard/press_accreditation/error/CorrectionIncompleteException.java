package com.presscard.press_accreditation.error;
/**
 * Resubmission attempted while flagged pieces remain unanswered. Refused,
 * because a partial answer would land on a reviewer who must re-request the
 * same correction — and the single round allowed has already been spent. → 422
 */
public class CorrectionIncompleteException extends RuntimeException {
    public CorrectionIncompleteException(String message) { super(message); }
}