//package com.presscard.press_accreditation.review;
//
///**
// * Why a file was rejected — typed, not merely narrated.
// *
// * The free-text justification records what was SAID; this records WHY in a
// * form the system can reason about. That distinction matters because one
// * ground carries a legal duty:
// *
// *   In the French administrative tradition, from which Mauritanian
// *   administrative law derives, an authority may not reject a file for
// *   INCOMPLETENESS without first inviting the applicant to complete it
// *   (cf. CRPA art. L. 114-5). A rejection on that ground with no prior
// *   correction request is therefore refused by ReviewService — the system
// *   will not let a reviewer take a decision that would fall at an objection.
// *
// * Substantive grounds carry no such duty: a candidate who is not a
// * journalist does not become one by being asked for another document.
// */
//public enum RejectionGround {
//
//    /** Documentary deficiency — REQUIRES a prior correction round. */
//    INCOMPLETE_FILE(
//            "Dossier incomplet",
//            "Les pièces justificatives fournies ne satisfont pas les exigences de la catégorie.",
//            true),
//
//    /** The candidate does not meet the criteria for the profession. */
//    INELIGIBLE(
//            "Non éligible",
//            "Le candidat ne remplit pas les conditions d'exercice de la profession.",
//            false),
//
//    /** Falsified or altered evidence. */
//    FRAUDULENT_DOCUMENT(
//            "Document frauduleux",
//            "Une ou plusieurs pièces présentent des indices de falsification.",
//            false),
//
//    /** Applied under a category that does not correspond to their situation. */
//    WRONG_CATEGORY(
//            "Catégorie inadaptée",
//            "La catégorie choisie ne correspond pas à la situation du candidat.",
//            false),
//
//    /** Anything else — the free text is doing the work. */
//    OTHER(
//            "Autre motif",
//            "Motif détaillé dans la justification.",
//            false);
//
//    private final String labelFr;
//    private final String descriptionFr;
//    private final boolean requiresPriorCorrection;
//
//    RejectionGround(String labelFr, String descriptionFr, boolean requiresPriorCorrection) {
//        this.labelFr = labelFr;
//        this.descriptionFr = descriptionFr;
//        this.requiresPriorCorrection = requiresPriorCorrection;
//    }
//
//    public String labelFr() { return labelFr; }
//    public String descriptionFr() { return descriptionFr; }
//
//    /**
//     * True when this ground may only be used after the candidate has been
//     * given a chance to correct — and has not.
//     */
//    public boolean requiresPriorCorrection() { return requiresPriorCorrection; }
//}

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
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE LABELS ARE BILINGUAL, AND THE ARABIC IS NOT DECORATION.
 *
 * The commission chooses a ground in the FRENCH admin space — but the ground
 * is then shown to the CANDIDATE, on the screen where they read their refusal
 * and on the one where they contest it. That reader may be Arabic.
 *
 * So the ground travels in both languages while the commission's own
 * interface stays French. The two are different audiences for the same
 * constant.
 *
 * ⚠️ The Arabic here states a LEGAL ground for refusing an accreditation. It
 * needs a native reading before deployment: the French is the reference text,
 * and the Arabic must say the same thing rather than something adjacent.
 * ───────────────────────────────────────────────────────────────────────
 */
public enum RejectionGround {

    /** Documentary deficiency — REQUIRES a prior correction round. */
    INCOMPLETE_FILE(
            "Dossier incomplet",
            "ملف غير مكتمل",
            "Les pièces justificatives fournies ne satisfont pas les exigences de la catégorie.",
            "الوثائق الثبوتية المقدمة لا تستوفي شروط الفئة.",
            true),

    /** The candidate does not meet the criteria for the profession. */
    INELIGIBLE(
            "Non éligible",
            "غير مؤهل",
            "Le candidat ne remplit pas les conditions d'exercice de la profession.",
            "المترشح لا يستوفي شروط ممارسة المهنة.",
            false),

    /** Falsified or altered evidence. */
    FRAUDULENT_DOCUMENT(
            "Document frauduleux",
            "وثيقة مزورة",
            "Une ou plusieurs pièces présentent des indices de falsification.",
            "تحمل وثيقة أو أكثر مؤشرات على التزوير.",
            false),

    /** Applied under a category that does not correspond to their situation. */
    WRONG_CATEGORY(
            "Catégorie inadaptée",
            "فئة غير ملائمة",
            "La catégorie choisie ne correspond pas à la situation du candidat.",
            "الفئة المختارة لا تطابق وضعية المترشح.",
            false),

    /** Anything else — the free text is doing the work. */
    OTHER(
            "Autre motif",
            "مبرر آخر",
            "Motif détaillé dans la justification.",
            "المبرر مفصل في التعليل.",
            false);

    private final String labelFr;
    private final String labelAr;
    private final String descriptionFr;
    private final String descriptionAr;
    private final boolean requiresPriorCorrection;

    RejectionGround(String labelFr, String labelAr,
                    String descriptionFr, String descriptionAr,
                    boolean requiresPriorCorrection) {
        this.labelFr = labelFr;
        this.labelAr = labelAr;
        this.descriptionFr = descriptionFr;
        this.descriptionAr = descriptionAr;
        this.requiresPriorCorrection = requiresPriorCorrection;
    }

    public String labelFr() { return labelFr; }
    public String labelAr() { return labelAr; }
    public String descriptionFr() { return descriptionFr; }
    public String descriptionAr() { return descriptionAr; }

    /**
     * True when this ground may only be used after the candidate has been
     * given a chance to correct — and has not.
     */
    public boolean requiresPriorCorrection() { return requiresPriorCorrection; }
}
