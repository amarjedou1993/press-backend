package com.presscard.press_accreditation.session;

import java.util.Optional;

/**
 * The phases a candidacy session moves through, in order.
 *
 * A SESSION IS A CYCLE, not a set of flags: each phase follows exactly one
 * other, and the whole system's behaviour hangs off which one is current —
 * whether candidates may submit, whether the commission may decide, whether a
 * rejected candidate may still object.
 *
 * THE FRENCH LABELS LIVE HERE rather than only in the frontend's PHASE_LABELS
 * map. They now appear in an exported spreadsheet as well as on screen, and a
 * string that exists only in TypeScript cannot reach a file the Authority
 * reports with. The same reasoning put labelFr() on ApplicationStatus and
 * CardStatus.
 */
public enum SessionStatus {

    /** Created, dates fixed, not yet open to candidates. */
    PLANNED("Programmée", "مبرمجة"),

    /** Candidates may deposit and submit. */
    RECEIVING("Réception des dossiers", "استقبال الملفات"),

    /** The commission examines what was submitted. */
    REVIEW("Examen", "دراسة"),

    /** Candidates answer the corrections asked of them. */
    CORRECTION("Correction", "تصحيح"),

    /** Rejected candidates may contest, once. */
    RECLAMATION("Réclamation", "تظلم"),

    /** Nothing further may happen to this session's dossiers. */
    CLOSED("Close", "مغلقة");

    private final String labelFr;
    private final String labelAr;

    SessionStatus(String labelFr, String labelAr) {
        this.labelFr = labelFr;
        this.labelAr = labelAr;
    }

    /** The phase as HAPA names it. */
    public String labelFr() {
        return labelFr;
    }

    /** For the public pages and the candidate space, which are bilingual. */
    public String labelAr() {
        return labelAr;
    }

    /** The next phase, or empty if already CLOSED (nothing follows). */
    public Optional<SessionStatus> next() {
        return switch (this) {
            case PLANNED     -> Optional.of(RECEIVING);
            case RECEIVING   -> Optional.of(REVIEW);
            case REVIEW      -> Optional.of(CORRECTION);
            case CORRECTION  -> Optional.of(RECLAMATION);
            case RECLAMATION -> Optional.of(CLOSED);
            case CLOSED      -> Optional.empty();
        };
    }

    /** Candidates may submit only while the session is receiving. */
    public boolean acceptsSubmissions() {
        return this == RECEIVING;
    }

    /**
     * Whether the session is under way — neither waiting to open nor finished.
     *
     * The condition appeared verbatim in three places (the admin dashboard's
     * "active session", the results screen, the public sessions page), and a
     * rule spelled out repeatedly is a rule that eventually differs somewhere.
     */
    public boolean isRunning() {
        return this != PLANNED && this != CLOSED;
    }
}