package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.CompletenessService;
import com.presscard.press_accreditation.document.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Candidate-facing contracts. Everything the wizard needs to render itself
 * comes from these — including the readiness checklist, so the UI never
 * re-implements a rule the backend owns.
 */
public final class ApplicationDtos {

    private ApplicationDtos() {}

    /* ── requests ── */

    public record StartApplicationRequest(
            @NotNull Long sessionId,
            @NotNull Long categoryId
    ) {}

    public record AttachLinkRequest(
            @NotNull DocumentType docType,
            @NotBlank @URL(message = "Adresse invalide (exemple : https://exemple.mr/article)")
            @Size(max = 1000) String url
    ) {}

    /* ── responses ── */

    public record DocumentResponse(
            Long id,
            String docType,
            String docTypeLabelFr,
            String kind,
            String url,
            boolean needsCorrection,
            String observation,
            int version,
            OffsetDateTime uploadedAt
    ) {
        public static DocumentResponse of(ApplicationDocument d) {
            return new DocumentResponse(
                    d.getId(), d.getDocType().name(), d.getDocType().labelFr(),
                    d.getKind().name(), d.getUrl(),
                    d.isNeedsCorrection(), d.getObservation(),
                    d.getVersion(), d.getUploadedAt());
            // NOTE: filePath is deliberately NOT exposed. Files are served
            // through a controlled endpoint, never by path.
        }
    }

    public record TimelineEntry(
            String fromStatus,
            String toStatus,
            String toStatusLabelFr,
            String justification,
            OffsetDateTime at
    ) {
        public static TimelineEntry of(StatusHistory h) {
            return new TimelineEntry(
                    h.getFromStatus() == null ? null : h.getFromStatus().name(),
                    h.getToStatus().name(),
                    h.getToStatus().labelFr(),
                    h.getJustification(),
                    h.getCreatedAt());
        }
    }

    public record ApplicationResponse(
            Long id,
            Long sessionId,
            Long categoryId,
            String status,
            String statusLabelFr,
            int correctionCount,
            OffsetDateTime submittedAt,
            OffsetDateTime createdAt,
            boolean editable
    ) {
        public static ApplicationResponse of(Application a) {
            return new ApplicationResponse(
                    a.getId(), a.getSessionId(), a.getCategoryId(),
                    a.getStatus().name(), a.getStatus().labelFr(),
                    a.getCorrectionCount(), a.getSubmittedAt(), a.getCreatedAt(),
                    a.getStatus().isEditableByCandidate());
        }
    }

    /** The wizard's full picture in one call. */
    public record ApplicationDetailResponse(
            ApplicationResponse application,
            List<DocumentResponse> documents,
            List<TimelineEntry> timeline,
            ReadinessResponse readiness
    ) {}

    /** The submission checklist — what is met, what is missing, and why. */
    public record ReadinessResponse(
            boolean canSubmit,
            List<BlockerResponse> blockers,
            boolean documentsComplete,
            List<RequirementResponse> mandatory,
            List<AlternativeGroupResponse> alternativeGroups,
            List<String> missingFr
    ) {
        public static ReadinessResponse of(SubmissionGate.GateResult gate) {
            CompletenessService.CompletenessResult c = gate.completeness();
            return new ReadinessResponse(
                    gate.allowed(),
                    gate.blockers().stream()
                            .map(b -> new BlockerResponse(b.reason().name(), b.messageFr()))
                            .toList(),
                    c.complete(),
                    c.mandatory().stream().map(RequirementResponse::of).toList(),
                    c.alternativeGroups().stream()
                            .map(g -> new AlternativeGroupResponse(
                                    g.groupNumber(), g.satisfied(),
                                    g.options().stream().map(RequirementResponse::of).toList()))
                            .toList(),
                    c.missingFr());
        }
    }

    public record BlockerResponse(String reason, String message) {}

    public record RequirementResponse(
            String docType,
            String labelFr,
            String labelAr,
            boolean isFile,
            int required,
            int provided,
            boolean satisfied
    ) {
        static RequirementResponse of(CompletenessService.RequirementStatus r) {
            return new RequirementResponse(
                    r.docType().name(), r.labelFr(), r.labelAr(),
                    r.isFile(), r.required(), r.provided(), r.satisfied());
        }
    }

    public record AlternativeGroupResponse(
            int groupNumber,
            boolean satisfied,
            List<RequirementResponse> options
    ) {}
}
