package com.presscard.press_accreditation.application;

import java.util.Set;

/**
 * The nine states of an application (V1.3 §E/§G), matching the CHECK
 * constraint in V1__init.sql exactly.
 *
 * The transitions encoded here are the regulation in code form:
 *
 *   DRAFT ──submit──▶ UNDER_REVIEW
 *                        ├─approve──▶ ACCEPTED ──issue──▶ CARD_ISSUED
 *                        ├─reject───▶ REJECTED ──objection──▶ UNDER_RECLAMATION
 *                        └─correction▶ CORRECTION_REQUESTED
 *                                        └─resubmit──▶ UNDER_FINAL_REVIEW
 *                                                        ├─approve──▶ ACCEPTED
 *                                                        └─reject───▶ REJECTED
 *   UNDER_RECLAMATION ├─approve──▶ ACCEPTED
 *                     └─reject───▶ FINAL_REJECTION
 *
 * CARD_ISSUED and FINAL_REJECTION are terminal.
 */
public enum ApplicationStatus {
    DRAFT,
    UNDER_REVIEW,
    CORRECTION_REQUESTED,
    UNDER_FINAL_REVIEW,
    ACCEPTED,
    REJECTED,
    UNDER_RECLAMATION,
    FINAL_REJECTION,
    CARD_ISSUED;

    /** States the candidate may still edit documents in. */
    public boolean isEditableByCandidate() {
        return this == DRAFT || this == CORRECTION_REQUESTED;
    }

    /** States where nothing further can happen. */
    public boolean isTerminal() {
        return this == CARD_ISSUED || this == FINAL_REJECTION;
    }

    /** States a reviewer may act on. */
    public boolean isAwaitingReview() {
        return this == UNDER_REVIEW || this == UNDER_FINAL_REVIEW || this == UNDER_RECLAMATION;
    }

    /** The legal next states — the single source of truth for transitions. */
    public Set<ApplicationStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(UNDER_REVIEW);
            case UNDER_REVIEW -> Set.of(ACCEPTED, REJECTED, CORRECTION_REQUESTED);
            case CORRECTION_REQUESTED -> Set.of(UNDER_FINAL_REVIEW, REJECTED);
            case UNDER_FINAL_REVIEW -> Set.of(ACCEPTED, REJECTED);
            case REJECTED -> Set.of(UNDER_RECLAMATION);
            case UNDER_RECLAMATION -> Set.of(ACCEPTED, FINAL_REJECTION);
            case ACCEPTED -> Set.of(CARD_ISSUED);
            case FINAL_REJECTION, CARD_ISSUED -> Set.of();
        };
    }

    public boolean canTransitionTo(ApplicationStatus target) {
        return allowedNext().contains(target);
    }

    /** French label for the UI — never show a raw enum name to a candidate. */
    public String labelFr() {
        return switch (this) {
            case DRAFT -> "Brouillon";
            case UNDER_REVIEW -> "En cours d'examen";
            case CORRECTION_REQUESTED -> "Correction demandée";
            case UNDER_FINAL_REVIEW -> "Examen final";
            case ACCEPTED -> "Acceptée";
            case REJECTED -> "Rejetée";
            case UNDER_RECLAMATION -> "Réclamation en cours";
            case FINAL_REJECTION -> "Rejet définitif";
            case CARD_ISSUED -> "Carte éditée";
        };
    }
}
