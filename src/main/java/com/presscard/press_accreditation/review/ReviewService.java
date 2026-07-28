package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.ApplicationDocumentRepository;
import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.error.CorrectionRequiredFirstException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The commission's side of an application: take a file, examine it, decide.
 *
 * FOUR RULES RUN THROUGH EVERYTHING HERE.
 *
 * 1. CLAIMING IS THE ASSIGNMENT. A dossier must be claimed before it can be
 *    decided, and only its claimer may decide it. Without this, two members
 *    could examine the same file simultaneously and reach different
 *    conclusions — and only one of them would be recorded.
 *
 * 2. A REJECTION IS ALWAYS EXPLAINED. Justification is mandatory, because
 *    the candidate has an objection right and that right is meaningless if
 *    they do not know what they are objecting to.
 *
 * 3. A FILE MAY NOT BE REJECTED AS INCOMPLETE WITHOUT A CHANCE TO COMPLETE
 *    IT. In the French administrative tradition, from which Mauritanian
 *    administrative law derives, an authority must invite completion before
 *    rejecting for incompleteness (cf. CRPA art. L. 114-5). The service
 *    refuses such a decision rather than let a reviewer take one that would
 *    fall at an objection.
 *
 * 4. EVERY STATUS CHANGE GOES THROUGH ApplicationService.transition(), which
 *    validates against the state machine and writes the history row in the
 *    same transaction. This service never sets a status directly.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger("REVIEW_AUDIT");

    private final ApplicationRepository applicationRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final ReviewDecisionRepository decisionRepository;
    private final ApplicationService applicationService;
    private final EmailService emailService;
    private final AppProperties props;

    public ReviewService(ApplicationRepository applicationRepository,
                         ApplicationDocumentRepository documentRepository,
                         ReviewDecisionRepository decisionRepository,
                         ApplicationService applicationService,
                         EmailService emailService,
                         AppProperties props) {
        this.applicationRepository = applicationRepository;
        this.documentRepository = documentRepository;
        this.decisionRepository = decisionRepository;
        this.applicationService = applicationService;
        this.emailService = emailService;
        this.props = props;
    }

    /* ══ the pool ═════════════════════════════════════════════ */

    /** Unclaimed dossiers awaiting examination, oldest submission first. */
    @Transactional(readOnly = true)
    public List<Application> pool() {
        return applicationRepository.findUnclaimedAwaitingReview();
    }

    /** What this reviewer currently holds. */
    @Transactional(readOnly = true)
    public List<Application> myClaims(Long reviewerId) {
        return applicationRepository.findByClaimedByOrderByClaimedAtAsc(reviewerId);
    }

    /* ══ claiming ═════════════════════════════════════════════ */

    /**
     * Take a dossier out of the pool.
     *
     * The repository update is CONDITIONAL — it only writes where claimed_by
     * is still null — so two reviewers clicking at the same moment cannot both
     * succeed. The loser gets a clear message rather than a silent overwrite.
     */
    @Transactional
    public Application claim(Long applicationId, Long reviewerId) {
        Application application = find(applicationId);

        if (!application.getStatus().isAwaitingReview()) {
            throw new NotAwaitingReviewException(
                    "Cette candidature n'est pas en attente d'examen (%s)."
                            .formatted(application.getStatus().labelFr()));
        }
        if (reviewerId.equals(application.getClaimedBy())) {
            return application;                       // idempotent
        }

        int claimed = applicationRepository.claimIfUnclaimed(
                applicationId, reviewerId, OffsetDateTime.now());
        if (claimed == 0) {
            throw new AlreadyClaimedException(
                    "Cette candidature vient d'être prise en charge par un autre membre "
                  + "de la commission.");
        }

        log.info("REVIEW_CLAIMED application={} reviewer={}", applicationId, reviewerId);
        return find(applicationId);
    }

    /** Put it back in the pool — a reviewer who cannot proceed must not block it. */
    @Transactional
    public Application release(Long applicationId, Long reviewerId, boolean isAdmin) {
        Application application = find(applicationId);

        if (application.getClaimedBy() == null) {
            return application;                       // idempotent
        }
        // An admin may force-release: a reviewer's absence must not freeze a
        // candidate's file indefinitely.
        if (!isAdmin && !reviewerId.equals(application.getClaimedBy())) {
            throw new NotYourClaimException(
                    "Cette candidature est prise en charge par un autre membre de la commission.");
        }

        Long previous = application.getClaimedBy();
        application.setClaimedBy(null);
        application.setClaimedAt(null);
        applicationRepository.save(application);

        log.info("REVIEW_RELEASED application={} from={} by={} forced={}",
                applicationId, previous, reviewerId, isAdmin);
        return application;
    }

    /* ══ the three decisions ══════════════════════════════════ */

    @Transactional
    public Application approve(Long applicationId, Long reviewerId, String note) {
        Application application = requireClaimedBy(applicationId, reviewerId);
        ReviewRound round = roundFor(application);

        record(application, reviewerId, DecisionType.APPROVE, note, null, round);
        applicationService.transition(application, ApplicationStatus.ACCEPTED, reviewerId, note);

        notifyCandidate(application, DecisionType.APPROVE, note);
        log.info("REVIEW_APPROVED application={} reviewer={} round={}",
                applicationId, reviewerId, round);
        return application;
    }

    /**
     * Reject, with a ground and a justification the candidate will read.
     *
     * @throws CorrectionRequiredFirstException when the ground is
     *         INCOMPLETE_FILE and no correction round has been offered — see
     *         rule 3 in the class javadoc.
     */
    @Transactional
    public Application reject(Long applicationId, Long reviewerId,
                              RejectionGround ground, String justification) {
        Application application = requireClaimedBy(applicationId, reviewerId);

        if (ground == null) {
            throw new JustificationRequiredException("Sélectionnez un motif de rejet.");
        }
        if (justification == null || justification.isBlank()) {
            throw new JustificationRequiredException(
                    "Une justification est obligatoire : le candidat doit savoir "
                  + "ce qui lui est reproché pour pouvoir exercer son droit de réclamation.");
        }

        // The legal duty: no rejection for incompleteness without a chance to complete.
        if (ground.requiresPriorCorrection() && application.getCorrectionCount() == 0) {
            throw new CorrectionRequiredFirstException(
                    "Un dossier ne peut pas être rejeté pour incomplétude sans qu'une "
                  + "correction ait d'abord été demandée au candidat. Demandez une "
                  + "correction, ou choisissez un autre motif si le rejet porte sur le fond.");
        }

        ReviewRound round = roundFor(application);
        record(application, reviewerId, DecisionType.REJECT, justification, ground, round);
        applicationService.transition(
                application, ApplicationStatus.REJECTED, reviewerId, justification);

        notifyCandidate(application, DecisionType.REJECT, justification);
        log.info("REVIEW_REJECTED application={} reviewer={} ground={} round={}",
                applicationId, reviewerId, ground, round);
        return application;
    }

    /**
     * Ask the candidate to replace specific documents.
     *
     * Flags are PER DOCUMENT with an observation each, so the candidate is
     * told exactly what to fix rather than being sent away with "incomplete".
     * The photograph has its own flag: it is the one element that cannot be
     * corrected after the card is printed.
     */
    @Transactional
    public Application requestCorrection(Long applicationId, Long reviewerId,
                                         String summary,
                                         List<DocumentFlag> documentFlags,
                                         boolean photoNeedsCorrection,
                                         String photoObservation) {
        Application application = requireClaimedBy(applicationId, reviewerId);

        if (summary == null || summary.isBlank()) {
            throw new JustificationRequiredException(
                    "Indiquez ce que le candidat doit corriger.");
        }
        if ((documentFlags == null || documentFlags.isEmpty()) && !photoNeedsCorrection) {
            throw new JustificationRequiredException(
                    "Signalez au moins une pièce à corriger : une demande de correction "
                  + "sans pièce identifiée ne dit pas au candidat quoi faire.");
        }
        // V1.3 §H — one round only. The DB CHECK backs this up.
        if (application.getCorrectionCount() >= props.application().maxCorrectionRounds()) {
            throw new CorrectionRoundExhaustedException(
                    "Une correction a déjà été demandée pour ce dossier. "
                  + "Le règlement n'en prévoit qu'une seule.");
        }

        // Flag the named documents.
        if (documentFlags != null) {
            for (DocumentFlag flag : documentFlags) {
                ApplicationDocument document = documentRepository.findById(flag.documentId())
                        .orElseThrow(() -> new DocumentNotFoundException(flag.documentId()));
                if (!document.getApplicationId().equals(applicationId)) {
                    throw new DocumentNotFoundException(flag.documentId());
                }
                document.setNeedsCorrection(true);
                document.setObservation(flag.observation());
                documentRepository.save(document);
            }
        }

        application.setPhotoNeedsCorrection(photoNeedsCorrection);
        application.setPhotoObservation(photoNeedsCorrection ? photoObservation : null);
        application.setCorrectionCount(application.getCorrectionCount() + 1);

        ReviewRound round = roundFor(application);
        record(application, reviewerId, DecisionType.REQUEST_CORRECTION, summary, null, round);
        applicationService.transition(
                application, ApplicationStatus.CORRECTION_REQUESTED, reviewerId, summary);

        // The dossier goes back to the candidate, so the claim ends with it.
        application.setClaimedBy(null);
        application.setClaimedAt(null);
        applicationRepository.save(application);

        notifyCandidate(application, DecisionType.REQUEST_CORRECTION, summary);
        log.info("REVIEW_CORRECTION_REQUESTED application={} reviewer={} documents={} photo={}",
                applicationId, reviewerId,
                documentFlags == null ? 0 : documentFlags.size(), photoNeedsCorrection);
        return application;
    }

    /** One flagged document and what is wrong with it. */
    public record DocumentFlag(Long documentId, String observation) {}

    /* ══ internals ════════════════════════════════════════════ */

    /**
     * Which round this decision belongs to, derived from the application's
     * state rather than trusted from the caller — the round determines the
     * UNIQUE constraint, so it must not be spoofable.
     */
    private ReviewRound roundFor(Application application) {
        return switch (application.getStatus()) {
            case UNDER_REVIEW -> ReviewRound.INITIAL;
            case UNDER_FINAL_REVIEW -> ReviewRound.FINAL;
            case UNDER_RECLAMATION -> ReviewRound.RECLAMATION;
            default -> throw new NotAwaitingReviewException(
                    "Cette candidature n'est pas en attente d'examen (%s)."
                            .formatted(application.getStatus().labelFr()));
        };
    }

    private void record(Application application, Long reviewerId, DecisionType type,
                        String justification, RejectionGround ground, ReviewRound round) {
        if (decisionRepository.existsByApplicationIdAndRound(application.getId(), round)) {
            throw new InvalidTokenException.AlreadyDecidedException(
                    "Une décision a déjà été enregistrée pour cette phase d'examen.");
        }
        decisionRepository.save(ReviewDecision.builder()
                .applicationId(application.getId())
                .reviewerId(reviewerId)
                .decision(type)
                .justification(justification)
                .rejectionGround(ground)
                .round(round)
                .build());
    }

    /** Claimed, by this reviewer, and still awaiting a decision. */
    private Application requireClaimedBy(Long applicationId, Long reviewerId) {
        Application application = find(applicationId);

        if (!application.getStatus().isAwaitingReview()) {
            throw new NotAwaitingReviewException(
                    "Cette candidature n'est pas en attente d'examen (%s)."
                            .formatted(application.getStatus().labelFr()));
        }
        if (application.getClaimedBy() == null) {
            throw new NotYourClaimException(
                    "Prenez d'abord ce dossier en charge avant de vous prononcer.");
        }
        if (!reviewerId.equals(application.getClaimedBy())) {
            throw new NotYourClaimException(
                    "Ce dossier est pris en charge par un autre membre de la commission.");
        }
        return application;
    }

    private Application find(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
    }

    /**
     * Load a dossier for examination.
     *
     * A reviewer may open ANY dossier that has been submitted — reading is
     * how the commission works, and restricting reads to the claimer would
     * stop a second opinion being sought. Only DECIDING requires the claim.
     * Drafts remain invisible: nothing unsubmitted is the commission's
     * business.
     */
    @Transactional(readOnly = true)
    public Application findForReview(Long applicationId) {
        Application application = find(applicationId);
        if (application.getStatus() == ApplicationStatus.DRAFT) {
            // 404 rather than 403: an unsubmitted dossier does not exist as
            // far as the commission is concerned.
            throw new ApplicationNotFoundException(applicationId);
        }
        return application;
    }

    /** Queued in this transaction: the mail cannot outlive a rolled-back decision. */
    private void notifyCandidate(Application application, DecisionType type, String message) {
        emailService.sendDecisionNotice(application.getCandidateId(),
                application.getId(), type.name(), message);
    }
}
