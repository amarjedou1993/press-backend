package com.presscard.press_accreditation.error;

/**
 * No commission member other than the rejecter exists, so V1.3 §J cannot be
 * honoured.
 *
 * This is an INSTITUTIONAL failure, not a user error: the candidate has done
 * nothing wrong and can do nothing about it. It is refused at filing time
 * rather than allowed to stall silently, so that an administrator learns of
 * it while the phase is still open and can appoint someone.
 * → 409, and logged at ERROR.
 */
public class NoEligibleReviewerException extends RuntimeException {
    public NoEligibleReviewerException(String message) { super(message); }
}
