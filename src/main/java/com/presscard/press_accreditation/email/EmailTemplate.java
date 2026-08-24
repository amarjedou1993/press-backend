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

    /* ── the card in circulation ── */
    CARD_SUSPENDED(false),
    CARD_REVOKED(false),
    CARD_REINSTATED(false),

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
