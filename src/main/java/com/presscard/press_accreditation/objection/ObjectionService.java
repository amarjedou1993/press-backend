package com.presscard.press_accreditation.objection;

import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.review.*;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.session.SessionStatus;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The candidate's one recourse against a refusal.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ WHY A BLOCKER IS NOW A CODE.
 *
 * `whyNot` returned a French sentence, which the screen displayed. On an
 * Arabic page that is a French paragraph explaining why someone may not
 * contest their own refusal — the worst place in the system for it.
 *
 * It returns a REASON now. The screen translates it, and the deadline
 * travels as a DATE rather than embedded in a formatted string, so an Arabic
 * page shows an Arabic date.
 *
 * The French and Arabic sentences remain for logs and for the exception
 * message, which is what an administrator reads.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class ObjectionService {

    private static final Logger log = LoggerFactory.getLogger("OBJECTION_AUDIT");

    /** Enough to say something a second reviewer can act on. */
    public static final int MIN_ARGUMENT_LENGTH = 30;

    /** Why an objection cannot be filed. The screen's translation key. */
    public enum BlockedReason {
        ALREADY_FILED,
        NOT_REJECTED,
        PHASE_NOT_OPEN,
        DEADLINE_PASSED,
        /** No second member exists to re-examine — an operational failure. */
        NO_ELIGIBLE_REVIEWER
    }

    private final ObjectionRepository objectionRepository;
    private final ObjectionReasonRepository reasonRepository;
    private final ApplicationRepository applicationRepository;
    private final ReviewDecisionRepository decisionRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ApplicationService applicationService;
    private final EmailService emailService;

    public ObjectionService(ObjectionRepository objectionRepository,
                            ObjectionReasonRepository reasonRepository,
                            ApplicationRepository applicationRepository,
                            ReviewDecisionRepository decisionRepository,
                            SessionRepository sessionRepository,
                            UserRepository userRepository,
                            ApplicationService applicationService,
                            EmailService emailService) {
        this.objectionRepository = objectionRepository;
        this.reasonRepository = reasonRepository;
        this.applicationRepository = applicationRepository;
        this.decisionRepository = decisionRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.applicationService = applicationService;
        this.emailService = emailService;
    }

    /* ══ what the candidate may do ═════════════════════════════ */

    /**
     * Whether an objection is possible, and if not, why not.
     *
     * @param blockedReason   the machine-readable cause — THE TRANSLATION KEY
     * @param blockedReasonFr the same in French, for logs
     * @param blockedReasonAr the same in Arabic
     */
    public record ObjectionEligibility(
            boolean canObject,
            String blockedReason,
            String blockedReasonFr,
            String blockedReasonAr,
            LocalDate deadline,
            long daysRemaining,
            boolean alreadyFiled,
            /** The rejection being contested, for the form's context. */
            String contestedJustification,
            String contestedGroundLabelFr,
            String contestedGroundLabelAr
    ) {}

    @Transactional(readOnly = true)
    public ObjectionEligibility eligibility(Long applicationId, Long candidateId) {
        Application application = findOwned(applicationId, candidateId);
        Session session = sessionRepository.findById(application.getSessionId()).orElseThrow();

        ReviewDecision rejection = latestRejection(applicationId);
        LocalDate deadline = session.getReclamationEnd();
        long remaining = deadline == null ? 0
                : ChronoUnit.DAYS.between(LocalDate.now(), deadline);

        boolean already = objectionRepository.existsByApplicationId(applicationId);
        String contested = rejection == null ? null : rejection.getJustification();

        RejectionGround groundEnum = rejection == null ? null : rejection.getRejectionGround();
        String groundFr = groundEnum == null ? null : groundEnum.labelFr();
        String groundAr = groundEnum == null ? null : groundEnum.labelAr();

        BlockedReason blocked = whyNot(application, session, already);

        return new ObjectionEligibility(
                blocked == null,
                blocked == null ? null : blocked.name(),
                blocked == null ? null : messageFr(blocked, deadline),
                blocked == null ? null : messageAr(blocked, deadline),
                deadline, Math.max(remaining, 0),
                already, contested, groundFr, groundAr);
    }

    /** The single place that decides whether an objection may be filed. */
    private BlockedReason whyNot(Application application, Session session, boolean already) {
        if (already) {
            return BlockedReason.ALREADY_FILED;
        }
        if (application.getStatus() != ApplicationStatus.REJECTED) {
            return BlockedReason.NOT_REJECTED;
        }
        if (session.getStatus() != SessionStatus.RECLAMATION) {
            return BlockedReason.PHASE_NOT_OPEN;
        }
        if (session.getReclamationEnd() != null
                && LocalDate.now().isAfter(session.getReclamationEnd())) {
            return BlockedReason.DEADLINE_PASSED;
        }
        return null;
    }

    /* ── the sentences, for logs and exception messages ── */

    private static String messageFr(BlockedReason reason, LocalDate deadline) {
        return switch (reason) {
            case ALREADY_FILED -> "Vous avez déjà déposé une réclamation pour ce dossier. "
                    + "Le règlement n'en prévoit qu'une seule.";
            case NOT_REJECTED -> "Une réclamation ne peut être déposée que contre une "
                    + "décision de rejet.";
            case PHASE_NOT_OPEN -> "La phase de réclamation de cette session n'est pas ouverte.";
            case DEADLINE_PASSED -> "Le délai de réclamation est expiré (%s).".formatted(deadline);
            case NO_ELIGIBLE_REVIEWER -> "Votre réclamation ne peut pas être enregistrée "
                    + "pour le moment : aucun autre membre de la commission n'est disponible "
                    + "pour la réexaminer. Contactez le Ministère.";
        };
    }

    private static String messageAr(BlockedReason reason, LocalDate deadline) {
        return switch (reason) {
            case ALREADY_FILED -> "سبق أن أودعت تظلمًا بشأن هذا الملف. "
                    + "ولا يسمح النظام إلا بتظلم واحد.";
            case NOT_REJECTED -> "لا يودَع التظلم إلا ضد قرار رفض.";
            case PHASE_NOT_OPEN -> "مرحلة التظلم في هذه الدورة غير مفتوحة.";
            case DEADLINE_PASSED -> "انتهى أجل التظلم (%s).".formatted(deadline);
            case NO_ELIGIBLE_REVIEWER -> "لا يمكن تسجيل تظلمك في الوقت الحالي: "
                    + "لا يوجد عضو آخر في اللجنة متاح لإعادة دراسته. اتصل بالوزارة.";
        };
    }

    /* ══ filing ════════════════════════════════════════════════ */

    @Transactional
    public Objection file(Long applicationId, Long candidateId,
                          Long reasonId, String argument) {
        Application application = findOwned(applicationId, candidateId);
        Session session = sessionRepository.findById(application.getSessionId()).orElseThrow();

        BlockedReason blocked = whyNot(application, session,
                objectionRepository.existsByApplicationId(applicationId));
        if (blocked != null) {
            // A KEY on the wire, the French sentence in the log.
            log.info("OBJECTION_REFUSED application={} reason={}", applicationId, blocked);
            throw new ObjectionNotAllowedException("objectionBlocked." + blocked.name());
        }

        ObjectionReason reason = reasonRepository.findById(reasonId)
                .filter(ObjectionReason::isActive)
                .orElseThrow(() -> new ObjectionNotAllowedException(
                        "validation.groundInvalid"));

        if (argument == null || argument.trim().length() < MIN_ARGUMENT_LENGTH) {
            // ⚠️ The screen already enforces this with a live counter, so
            // reaching here means a direct API call — but the message must
            // still be readable, and it must still be a key.
            throw new ObjectionNotAllowedException("validation.argumentTooShort");
        }

        // Rule 4, checked BEFORE the objection is accepted: a right the
        // institution cannot honour should not be granted silently.
        requireAnEligibleReviewerExists(applicationId);

        ReviewDecision rejection = latestRejection(applicationId);

        Objection objection = objectionRepository.save(Objection.builder()
                .applicationId(applicationId)
                .reasonId(reason.getId())
                .argument(argument.trim())
                .contestedDecisionId(rejection == null ? null : rejection.getId())
                .build());

        // ⚠️ The transition note is an AUDIT record, read by staff in the
        // French admin space and stored for the life of the file. It stays
        // French deliberately — status_history is the legal trail, not a
        // screen, and a trail written in two languages is harder to read
        // than one written in the institution's working language.
        applicationService.transition(application, ApplicationStatus.UNDER_RECLAMATION,
                candidateId, "Réclamation déposée : " + reason.getLabelFr());

        // The file returns to the pool — for everyone EXCEPT its rejecter.
        application.setClaimedBy(null);
        application.setClaimedAt(null);
        applicationRepository.save(application);

        // ⚠️ The candidate's own language, not the institution's: this is a
        // message TO THEM. Once EmailService takes a locale, pass the
        // holder's preferredLocale and the matching label.
//        emailService.sendObjectionReceived(candidateId, applicationId, reason.getLabelFr());
        emailService.sendObjectionReceived(candidateId, applicationId, reason.getCode());
        log.info("OBJECTION_FILED application={} candidate={} reason={}",
                applicationId, candidateId, reason.getCode());
        return objection;
    }

    /**
     * At least one commission member other than the rejecter must exist.
     *
     * This is the operational failure the different-reviewer rule invites: it
     * is logically sound and practically impossible with a one-member
     * commission. Refusing at filing time turns a silent stall into a message
     * an administrator can act on — while the phase is still open.
     */
    private void requireAnEligibleReviewerExists(Long applicationId) {
        ReviewDecision rejection = latestRejection(applicationId);
        Long rejecterId = rejection == null ? null : rejection.getReviewerId();

        List<User> reviewers = userRepository.findByRoleAndEnabledTrue(UserRole.REVIEWER);
        boolean someoneElseExists = reviewers.stream()
                .anyMatch(r -> !r.getId().equals(rejecterId));

        if (!someoneElseExists) {
            log.error("OBJECTION_NO_ELIGIBLE_REVIEWER application={} rejecter={} "
                            + "activeReviewers={} — the commission cannot honour the objection right",
                    applicationId, rejecterId, reviewers.size());

            throw new NoEligibleReviewerException(
                    "objectionBlocked." + BlockedReason.NO_ELIGIBLE_REVIEWER.name());
        }
    }

    /* ══ reading ═══════════════════════════════════════════════ */

    /** The objection on a dossier, for the reviewer examining it. */
    @Transactional(readOnly = true)
    public Objection findByApplication(Long applicationId) {
        return objectionRepository.findByApplicationId(applicationId).orElse(null);
    }

    @Transactional(readOnly = true)
    public ObjectionReason reason(Long reasonId) {
        return reasonRepository.findById(reasonId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ObjectionReason> activeReasons() {
        return reasonRepository.findByActiveTrueOrderByDisplayOrderAsc();
    }

    /** Who rejected this dossier — the person barred from re-examining it. */
    @Transactional(readOnly = true)
    public Long rejecterOf(Long applicationId) {
        ReviewDecision rejection = latestRejection(applicationId);
        return rejection == null ? null : rejection.getReviewerId();
    }

    /* ══ internals ═════════════════════════════════════════════ */

    private ReviewDecision latestRejection(Long applicationId) {
        return decisionRepository
                .findByApplicationIdOrderByCreatedAtAsc(applicationId).stream()
                .filter(d -> d.getDecision() == DecisionType.REJECT
                        && d.getRound() != ReviewRound.RECLAMATION)
                .reduce((first, second) -> second)     // the most recent
                .orElse(null);
    }

    private Application findOwned(Long applicationId, Long candidateId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        if (!application.getCandidateId().equals(candidateId)) {
            // 404, not 403: another candidate's dossier does not exist for you.
            throw new ApplicationNotFoundException(applicationId);
        }
        return application;
    }
}
