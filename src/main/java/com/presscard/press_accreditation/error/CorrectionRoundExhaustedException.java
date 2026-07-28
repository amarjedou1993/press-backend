package com.presscard.press_accreditation.error;

/** The single correction round allowed by V1.3 §H has been used. → 409 */
public class CorrectionRoundExhaustedException extends RuntimeException {
    public CorrectionRoundExhaustedException(String message) {
        super(message);
    }
}
