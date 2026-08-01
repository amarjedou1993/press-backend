package com.presscard.press_accreditation.card;

/**
 * What a card is, right now.
 *
 * EXPIRED IS NOT HERE, deliberately. Expiry is a date fact derived from
 * expires_at, not a decision anyone takes. Storing it would mean a nightly job
 * could fail and a lapsed card would still read "valide" to whoever scanned
 * it — the one answer a verification page must never give wrongly.
 *
 * The three that ARE stored each represent an act by a person, and each
 * carries an actor, a reason and a timestamp.
 */
public enum CardStatus {

    /** Issued and in force. */
    VALID("Valide", "سارية"),

    /**
     * Temporarily withheld — a card reported lost or stolen, a holder under
     * investigation. PRECAUTIONARY and REVERSIBLE, which is why a super admin
     * may act alone: waiting for a committee while a stolen card circulates
     * helps nobody.
     */
    SUSPENDED("Suspendue", "موقوفة"),

    /**
     * Withdrawn. TERMINAL and PUNITIVE — it strips a journalist's
     * accreditation mid-year — so it follows the chain that granted it:
     * proposed by the commission, executed by the super admin.
     */
    REVOKED("Retirée", "مسحوبة");

    private final String labelFr;
    private final String labelAr;

    CardStatus(String labelFr, String labelAr) {
        this.labelFr = labelFr;
        this.labelAr = labelAr;
    }

    public String labelFr() { return labelFr; }
    public String labelAr() { return labelAr; }

    public boolean isInForce() {
        return this == VALID;
    }
}
