package com.presscard.press_accreditation.error;

/**
 * A rejection for INCOMPLETENESS was attempted without a prior correction
 * request.
 *
 * In the French administrative tradition, from which Mauritanian
 * administrative law derives, an authority may not reject a file as
 * incomplete without first inviting the applicant to complete it
 * (cf. CRPA art. L. 114-5). Refusing the decision here protects the
 * commission from taking one that would be overturned on objection.
 * → 409
 */
public class CorrectionRequiredFirstException extends RuntimeException {
    public CorrectionRequiredFirstException(String message) {
        super(message);
    }
}
