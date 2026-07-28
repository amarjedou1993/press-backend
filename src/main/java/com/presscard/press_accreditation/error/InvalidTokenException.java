package com.presscard.press_accreditation.error;

/**
 * An e-mail link that is missing, expired, already used, or of the wrong
 * type. All four produce the SAME message: a caller probing links must not
 * learn which of the four it hit. → 400
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }

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
    public static class CorrectionRequiredFirstException extends RuntimeException {
        public CorrectionRequiredFirstException(String message) {
            super(message);
        }
    }

    /** The single correction round allowed by V1.3 §H has been used. → 409 */
    public static class CorrectionRoundExhaustedException extends RuntimeException {
        public CorrectionRoundExhaustedException(String message) {
            super(message);
        }
    }

    /** A decision that must be explained was submitted without an explanation. → 400 */
    public static class JustificationRequiredException extends RuntimeException {
        public JustificationRequiredException(String message) {
            super(message);
        }
    }

    /** The dossier is not in a state the commission may act on. → 409 */
    public static class NotAwaitingReviewException extends RuntimeException {
        public NotAwaitingReviewException(String message) {
            super(message);
        }
    }

    /** Acting on a dossier claimed by someone else, or by nobody. → 409 */
    public static class NotYourClaimException extends RuntimeException {
        public NotYourClaimException(String message) {
            super(message);
        }
    }

    /** A decision already exists for this round. → 409 */
    public static class AlreadyDecidedException extends RuntimeException {
        public AlreadyDecidedException(String message) {
            super(message);
        }
    }

    /** Two reviewers claimed the same dossier at the same instant; one lost the race. → 409 */
    public static class AlreadyClaimedException extends RuntimeException {
        public AlreadyClaimedException(String message) {
            super(message);
        }
    }
}
