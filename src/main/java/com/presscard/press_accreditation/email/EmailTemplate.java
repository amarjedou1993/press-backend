package com.presscard.press_accreditation.email;

/**
 * Which message, not what it says.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE TEXT LEFT THIS FILE, AND THAT IS THE POINT.
 *
 * Fifteen templates in French were four hundred lines of Java string
 * literals. In two languages they would be nine hundred — and nobody who
 * revises official wording can work in a .java file. A ministry's
 * communications officer should be able to correct a sentence without a
 * compiler.
 *
 * The bodies now live in `messages/email_fr.properties` and
 * `messages/email_ar.properties`, keyed by the enum constant. Adding a
 * language means adding a file; changing a sentence means changing a line.
 *
 * What remains here is the CATALOGUE: which messages exist, and whether each
 * carries a call to action.
 * ───────────────────────────────────────────────────────────────────────
 */
public enum EmailTemplate {

    /* ── account lifecycle ── */
    VERIFY_EMAIL(true),
    PASSWORD_RESET(true),
    EMAIL_CHANGE(true),
    /** Sent to the OLD address as a warning — deliberately no link. */
    EMAIL_CHANGE_NOTICE(false),

    /* ── application lifecycle ── */
    APPLICATION_SUBMITTED(true),
    CORRECTION_REQUESTED(true),
    CORRECTION_DEADLINE_WARNING(true),
    CORRECTION_RESUBMITTED(true),
    APPLICATION_ACCEPTED(true),
    APPLICATION_REJECTED(true),
    OBJECTION_RECEIVED(true),
    CARD_ISSUED(true),
    /**
     * The renewal's card has been issued, and the previous one retired.
     *
     * ⚠️ A SEPARATE TEMPLATE, NOT CARD_ISSUED WITH AN EXTRA LINE.
     *
     * A renewal announces two facts at once — a new card, and an old one that
     * has stopped working — and the second is the one a holder must not
     * misread. CARD_ISSUED cannot carry it conditionally: a properties bundle
     * has no conditionals, and a sentence about a previous card would appear
     * on every first-time issuance.
     */
    CARD_RENEWED(true),
    /**
     * The renewal window is open, and this holder's card is in it.
     *
     * ⚠️ true, AND IT IS THE MOST LOAD-BEARING BUTTON IN THE SYSTEM.
     *
     * Every other action link takes someone to a dossier they already know
     * about. This one is the only notice that tells a holder something exists
     * at all — nobody watches a website for a session they have no reason to
     * expect, and an accreditation that lapses because its holder was never
     * told is an administrative failure rather than a candidate's.
     */
    RENEWAL_INVITATION(true),

    /** An institution's cards are approaching expiry. */
    INSTITUTIONAL_RENEWAL_DUE(true),

    /* ── the card in circulation ── */
    CARD_SUSPENDED(false),
    CARD_REVOKED(false),
    CARD_REINSTATED(false),

    /** What is waiting in the producer's queue. */
    PRINT_DIGEST(false),

    /* ── institution requests ── */

    /** Confirm the address a body applied with, before the Ministry reads it. */
    INSTITUTION_REQUEST_CONFIRM(true),
    /** The Ministry approved the request; the account is live. */
    INSTITUTION_REQUEST_APPROVED(true),
    /**
     * The Ministry refused the request, with its reason.
     *
     * ⚠️ false, LIKE THE CARD NOTICES. There is nothing to click through to:
     * the applicant has no account, and a button on a refusal reads as an
     * invitation to argue with it.
     */
    INSTITUTION_REQUEST_REJECTED(false),

    /* ── staff ── */
    REVOCATION_PROPOSED(true);



    private final boolean hasAction;

    EmailTemplate(boolean hasAction) {
        this.hasAction = hasAction;
    }

    /**
     * Whether the message ends with a button.
     *
     * ⚠️ EMAIL_CHANGE_NOTICE and the three card notices carry NONE, and that
     * is a decision rather than an omission: a warning sent to an address
     * that may have been compromised must not contain a link, and a
     * suspension notice should not look like something to click through.
     */
    public boolean hasAction() {
        return hasAction;
    }

    /** Message-bundle keys, derived so they cannot drift from the constant. */
    public String subjectKey() { return name() + ".subject"; }
    public String bodyKey()    { return name() + ".body"; }
    public String actionKey()  { return name() + ".action"; }
}
