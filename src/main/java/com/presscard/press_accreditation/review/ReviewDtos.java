package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.application.Application;
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
 * every document, the completeness breakdown and the decision history, all in
 * one call. A reviewer deciding someone's professional accreditation should
 * not be assembling the picture from four requests, and a partial view is how
 * a decision gets taken on incomplete information.
 */
public final class ReviewDtos {

    private ReviewDtos() {}

    /* ── requests ── */

    public record ApproveRequest(
            @Size(max = 2000) String note          // optional
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

    /* ── responses ── */

    /** A row in the pool or in a reviewer's workload. */
//    public record PoolItemResponse(
//            Long applicationId,
//            String candidateFullName,
//            String categoryLabelFr,
//            String status,
//            String statusLabelFr,
//            String roundLabelFr,
//            OffsetDateTime submittedAt,
//            /** Days since submission — the queue's fairness signal. */
//            long waitingDays,
//            Long claimedBy,
//            String claimedByName,
//            OffsetDateTime claimedAt,
//            int correctionCount
//    ) {}

    /** A row in any of the four lists. */
    public record PoolItemResponse(
            Long applicationId,
            String candidateFullName,
            String categoryLabelFr,
            String status,
            String statusLabelFr,
            String roundLabelFr,
            OffsetDateTime submittedAt,
            long waitingDays,
            Long claimedBy,
            String claimedByName,
            OffsetDateTime claimedAt,
            int correctionCount,
            // ── ADDED ──
            /** What THIS reviewer decided, if anything: APPROVE | REJECT | REQUEST_CORRECTION. */
            String myDecision,
            String myDecisionLabelFr,
            /** When they decided it. */
            OffsetDateTime myDecidedAt
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
            boolean photoAgeing
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
            // what this reviewer may do right now
            AvailableActions actions
    ) {}

    /**
     * What the reviewer may do, decided by the SERVER.
     *
     * The UI could infer most of this, but then two implementations of the
     * rules would exist and could drift. In particular
     * `canRejectAsIncomplete` encodes a legal duty — no rejection for
     * incompleteness without a prior correction — and that belongs in one
     * place only.
     */
    public record AvailableActions(
            boolean canClaim,
            boolean canRelease,
            boolean canDecide,
            boolean canRequestCorrection,
            boolean canRejectAsIncomplete,
            String correctionUnavailableReason,
            String incompleteRejectionUnavailableReason
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
