package com.presscard.press_accreditation.card;

/**
 * A revocation proposal, as both hands need to read it.
 *
 * ONE SHAPE FOR BOTH SIDES, deliberately. The commission member reviewing
 * their own proposals and the Authority deciding on them need the same facts —
 * what is alleged, against whom, by whom, and what came of it. Two DTOs would
 * drift, and the drift would be in the record of a withdrawal.
 *
 * NAMES, not ids. Nobody deciding whether to end an accreditation should be
 * cross-referencing user numbers, and the record of that decision should read
 * as a record rather than as a join.
 *
 * A TOP-LEVEL RECORD rather than nested in a controller: two controllers
 * return it, and nesting it in one of them would make the other import from a
 * class it has nothing else to do with.
 */
public record ProposalResponse(
        Long id,

        /* ── what it is against ── */
        Long cardId,
        String cardNumber,
        String holderFullName,
        /** The card's status right now — a proposal on a suspended card reads
            differently from one on a card still in force. */
        String cardStatus,
        String cardStatusLabelFr,

        /* ── what is alleged ── */
        Long groundId,
        String groundCode,
        String groundLabelFr,
        String groundLabelAr,
        /** True where proposing already suspended the card. */
        boolean warrantsImmediateSuspension,
        String statement,

        /* ── the first hand ── */
        Long proposedById,
        String proposedByName,
        String proposedAt,

        /* ── the second hand ── */
        String status,
        String statusLabelFr,
        String decidedByName,
        String decidedAt,
        String decidedNote
) {}
