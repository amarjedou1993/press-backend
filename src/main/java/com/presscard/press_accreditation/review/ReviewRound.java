package com.presscard.press_accreditation.review;

/**
 * Which examination a decision belongs to. UNIQUE (application_id, round) in
 * the database, so a file cannot be decided twice in the same round — the
 * constraint that makes the audit trail unambiguous.
 */
public enum ReviewRound {
    /** First examination, after submission. */
    INITIAL("Examen initial"),

    /** After the candidate answered a correction request. */
    FINAL("Examen final"),

    /** After an objection — and by law, a DIFFERENT reviewer (V1.3 §J). */
    RECLAMATION("Examen de la réclamation");

    private final String labelFr;

    ReviewRound(String labelFr) {
        this.labelFr = labelFr;
    }

    public String labelFr() {
        return labelFr;
    }
}
