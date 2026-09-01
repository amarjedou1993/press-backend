package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.document.CompletenessService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The commission's contracts.
 *
 * The examination response is deliberately COMPLETE: identity, photograph,
 * every document, the completeness breakdown, the decision history and — on a
 * reclamation — the contestation itself. A reviewer deciding someone's
 * professional accreditation should not be assembling the picture from four
 * requests, and a partial view is how a decision gets taken on incomplete
 * information.
 */
public final class ReviewDtos {

    private ReviewDtos() {}

    /* ══ requests ══ */

    public record ApproveRequest(
            @Size(max = 2000) String note
    ) {}

    public record RejectRequest(
            @NotNull RejectionGround ground,
            @NotBlank(message = "Une justification est obligatoire.")
            @Size(max = 4000) String justification
    ) {}

    public record DocumentFlagRequest(
            @NotNull Long documentId,
            @NotBlank(message = "Indiquez ce qui doit être corrigé.")
            @Size(max = 1000) String observation
    ) {}

    public record RequestCorrectionRequest(
            @NotBlank(message = "Résumez ce que le candidat doit corriger.")
            @Size(max = 4000) String summary,
            List<DocumentFlagRequest> documents,
            boolean photoNeedsCorrection,
            @Size(max = 1000) String photoObservation
    ) {}

    /* ══ responses ══ */

    /** A row in any of the four lists. */
    public record PoolItemResponse(
            Long applicationId,
            String candidateFullName,
            String categoryLabelFr,
            String status,
            String statusLabelFr,
            String roundLabelFr,
            OffsetDateTime submittedAt,
            /** Days since submission — the queue's fairness signal. */
            long waitingDays,
            Long claimedBy,
            String claimedByName,
            OffsetDateTime claimedAt,
            int correctionCount,
            /** What THIS reviewer decided, if anything — for the "Traités" tab. */
            String myDecision,
            String myDecisionLabelFr,
            OffsetDateTime myDecidedAt,
            /**
             * "Session du 12 mars 2026".
             *
             * ⚠️ Read only in "Mes décisions" — the one scope that crosses
             * sessions. In the working queue every row would carry the same
             * label, which is noise repeating the context rather than adding
             * to it. The screen decides whether to show it; this supplies it
             * either way.
             */
            String sessionLabel
    ) {}

    /** The candidate, as the commission needs to see them. */
    public record CandidateIdentityResponse(
            Long userId,
            String fullName,
            String email,
            String phone,
            String nni,
            String passportNo,
            String birthdate,
            String birthplace,
            boolean hasPhoto,
            boolean photoAgeing,
            /**
             * What they declared, and what the card will print. The commission
             * is being asked to verify precisely this against the work
             * certificate — "journaliste chez Mauri News" is the claim, and
             * the attestation is the evidence for it.
             */
            String specialisationLabelFr,
            String institution
    ) {}

    public record ReviewDocumentResponse(
            Long id,
            String docType,
            String docTypeLabelFr,
            String kind,
            String url,
            boolean needsCorrection,
            String observation,
            int version,
            OffsetDateTime uploadedAt
    ) {}

    public record DecisionHistoryEntry(
            String decision,
            String decisionLabelFr,
            String round,
            String roundLabelFr,
            String rejectionGround,
            String rejectionGroundLabelFr,
            String justification,
            String reviewerName,
            OffsetDateTime at
    ) {}

    /**
     * The contestation, on a RECLAMATION round.
     *
     * Carries BOTH SIDES deliberately: what the candidate disputes, and the
     * decision they dispute. A second reviewer who sees only the objection is
     * re-examining in the dark; one who sees only the rejection has no idea
     * what is being contested.
     */
    public record ObjectionSummary(
            String reasonLabelFr,
            String reasonLabelAr,
            String argument,
            OffsetDateTime filedAt,
            String contestedJustification,
            String contestedGroundLabelFr,
            /** Who rendered the contested decision — and may not re-examine it. */
            String contestedByName
    ) {}

    /** Everything needed to examine a dossier, in one response. */
    public record ExaminationResponse(
            Long applicationId,
            String status,
            String statusLabelFr,
            String currentRound,
            String currentRoundLabelFr,
            OffsetDateTime submittedAt,
            int correctionCount,
            int maxCorrectionRounds,
            boolean photoNeedsCorrection,
            String photoObservation,
            // who holds it
            Long claimedBy,
            String claimedByName,
            OffsetDateTime claimedAt,
            boolean claimedByMe,
            // the candidate
            CandidateIdentityResponse candidate,
            // the evidence
            List<ReviewDocumentResponse> documents,
            CompletenessService.CompletenessResult completeness,
            // what has already been decided
            List<DecisionHistoryEntry> history,
            /** Present only on a reclamation. */
            ObjectionSummary objection,
            // what this reviewer may do right now
            AvailableActions actions
    ) {}

    /**
     * What the reviewer may do, decided by the SERVER.
     *
     * The UI could infer most of this, but then two implementations of the
     * rules would exist and could drift. Two of these encode rules that must
     * not be duplicated: `canRejectAsIncomplete` carries a legal duty, and
     * `barredAsAuthor` carries V1.3 §J.
     */
    public record AvailableActions(
            boolean canClaim,
            boolean canRelease,
            boolean canDecide,
            boolean canRequestCorrection,
            boolean canRejectAsIncomplete,
            /** True when this reviewer authored the contested decision. */
            boolean barredAsAuthor,
            String correctionUnavailableReason,
            String incompleteRejectionUnavailableReason,
            String barredReason
    ) {}

    /** The rejection grounds, for the decision form. */
    public record RejectionGroundOption(
            String value,
            String labelFr,
            String descriptionFr,
            boolean requiresPriorCorrection,
            boolean availableNow
    ) {}
}
