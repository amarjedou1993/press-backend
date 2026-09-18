package com.presscard.press_accreditation.card;

import java.time.LocalDate;

/**
 * One holder and their card, assembled once and rendered anywhere.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ A FLAT ROW, BECAUSE TWO DOCUMENTS NEED THE SAME FACTS.
 *
 * The registry export builds an Excel file; the procès-verbal builds a Word
 * one. Both answer "who holds which card" — and before this record they would
 * have answered it with two loops, each making four lookups per card.
 *
 * One assembly, batched, feeding both renderers.
 *
 * ⚠️ AND IT CARRIES NO CONTACT DETAILS.
 *
 * Telephone and e-mail belong in the internal registry, which the HAPA uses
 * to reach people. They do not belong in a procès-verbal: that document is
 * signed, filed, and may be produced to a third party. A formal record of who
 * was accredited is not a contact list, and the difference is decided here
 * rather than left to whoever writes the next renderer.
 * ───────────────────────────────────────────────────────────────────────
 */
public record CardRegistryRow(
        String cardNumber,
        String fullName,
        String identityNumber,
        String categoryLabelFr,
        /** The outlet, where the series records one. Null for honour cards. */
        String institution,
        LocalDate issuedAt,
        LocalDate expiresAt,
        String statusLabelFr,
        /* ── registry only ── */
        String phone,
        String email
) {
    /**
     * The same row with contact details removed.
     *
     * ⚠️ CALLED BY THE PV RENDERER, so the omission is an explicit act rather
     * than a renderer quietly not reading two fields. A future column added to
     * this record will not silently reach a signed document.
     */
    public CardRegistryRow withoutContact() {
        return new CardRegistryRow(cardNumber, fullName, identityNumber,
                categoryLabelFr, institution, issuedAt, expiresAt,
                statusLabelFr, null, null);
    }
}
