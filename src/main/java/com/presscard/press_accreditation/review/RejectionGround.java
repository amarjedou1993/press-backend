package com.presscard.press_accreditation.review;

/**
 * Why a file was rejected — typed, not merely narrated.
 *
 * The free-text justification records what was SAID; this records WHY in a
 * form the system can reason about. That distinction matters because one
 * ground carries a legal duty:
 *
 *   In the French administrative tradition, from which Mauritanian
 *   administrative law derives, an authority may not reject a file for
 *   INCOMPLETENESS without first inviting the applicant to complete it
 *   (cf. CRPA art. L. 114-5). A rejection on that ground with no prior
 *   correction request is therefore refused by ReviewService — the system
 *   will not let a reviewer take a decision that would fall at an objection.
 *
 * Substantive grounds carry no such duty: a candidate who is not a
 * journalist does not become one by being asked for another document.
 */
public enum RejectionGround {

    /** Documentary deficiency — REQUIRES a prior correction round. */
    INCOMPLETE_FILE(
            "Dossier incomplet",
            "Les pièces justificatives fournies ne satisfont pas les exigences de la catégorie.",
            true),

    /** The candidate does not meet the criteria for the profession. */
    INELIGIBLE(
            "Non éligible",
            "Le candidat ne remplit pas les conditions d'exercice de la profession.",
            false),

    /** Falsified or altered evidence. */
    FRAUDULENT_DOCUMENT(
            "Document frauduleux",
            "Une ou plusieurs pièces présentent des indices de falsification.",
            false),

    /** Applied under a category that does not correspond to their situation. */
    WRONG_CATEGORY(
            "Catégorie inadaptée",
            "La catégorie choisie ne correspond pas à la situation du candidat.",
            false),

    /** Anything else — the free text is doing the work. */
    OTHER(
            "Autre motif",
            "Motif détaillé dans la justification.",
            false);

    private final String labelFr;
    private final String descriptionFr;
    private final boolean requiresPriorCorrection;

    RejectionGround(String labelFr, String descriptionFr, boolean requiresPriorCorrection) {
        this.labelFr = labelFr;
        this.descriptionFr = descriptionFr;
        this.requiresPriorCorrection = requiresPriorCorrection;
    }

    public String labelFr() { return labelFr; }
    public String descriptionFr() { return descriptionFr; }

    /**
     * True when this ground may only be used after the candidate has been
     * given a chance to correct — and has not.
     */
    public boolean requiresPriorCorrection() { return requiresPriorCorrection; }
}
