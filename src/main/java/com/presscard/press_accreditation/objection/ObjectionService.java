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
import java.util.List;

/**
 * The candidate's right to contest a rejection (V1.3 §J).
 *
 * FOUR RULES, and the last one is the difficult one.
 *
 * 1. ONCE ONLY. A UNIQUE constraint on application_id, not a check in code —
 *    a right that could be exercised twice could be exercised indefinitely.
 *
 * 2. ONLY AGAINST A REJECTION, and only during the session's reclamation
 *    phase. Outside that window the decision is settled.
 *
 * 3. A GROUND AND AN ARGUMENT. The predefined ground tells the second
 *    reviewer WHERE to look; the argument tells them WHAT is disputed. A
 *    ground alone would send someone to re-read an entire dossier with no
 *    idea what they are looking for.
 *
 * 4. A DIFFERENT REVIEWER MUST EXAMINE IT. This is the rule that can fail
 *    operationally rather than logically: with one active commission member,
 *    nobody is eligible, and the objection would sit unexaminable while the
 *    phase runs out. So the service CHECKS AT FILING TIME and says so — a
 *    candidate must not be given a right the institution cannot honour, and
 *    an administrator must learn about it while there is still time to
 *    appoint someone.
 */
@Service
public class ObjectionService {

    private static final Logger log = LoggerFactory.getLogger("OBJECTION_AUDIT");

    /** Enough to say something a second reviewer can act on. */
    private static final int MIN_ARGUMENT_LENGTH = 30;

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

    /** Whether an objection is possible, and if not, why not. */
    public record ObjectionEligibility(
            boolean canObject,
            String blockedReasonFr,
            LocalDate deadline,
            long daysRemaining,
            boolean alreadyFiled,
            /** The rejection being contested, for the form's context. */
            String contestedJustification,
            String contestedGroundLabelFr
    ) {}

    @Transactional(readOnly = true)
    public ObjectionEligibility eligibility(Long applicationId, Long candidateId) {
        Application application = findOwned(applicationId, candidateId);
        Session session = sessionRepository.findById(application.getSessionId()).orElseThrow();

        ReviewDecision rejection = latestRejection(applicationId);
        LocalDate deadline = session.getReclamationEnd();
        long remaining = deadline == null ? 0
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), deadline);

        boolean already = objectionRepository.existsByApplicationId(applicationId);
        String contested = rejection == null ? null : rejection.getJustification();
        String ground = rejection == null || rejection.getRejectionGround() == null
                ? null : rejection.getRejectionGround().labelFr();

        String blocked = whyNot(application, session, already);

        return new ObjectionEligibility(
                blocked == null, blocked, deadline, Math.max(remaining, 0),
                already, contested, ground);
    }

    /** The single place that decides whether an objection may be filed. */
    private String whyNot(Application application, Session session, boolean already) {
        if (already) {
            return "Vous avez déjà déposé une réclamation pour ce dossier. "
                 + "Le règlement n'en prévoit qu'une seule.";
        }
        if (application.getStatus() != ApplicationStatus.REJECTED) {
            return "Une réclamation ne peut être déposée que contre une décision de rejet.";
        }
        if (session.getStatus() != SessionStatus.RECLAMATION) {
            return "La phase de réclamation de cette session n'est pas ouverte.";
        }
        if (session.getReclamationEnd() != null
                && LocalDate.now().isAfter(session.getReclamationEnd())) {
            return "Le délai de réclamation est expiré (%s)."
                    .formatted(session.getReclamationEnd());
        }
        return null;
    }

    /* ══ filing ════════════════════════════════════════════════ */

    @Transactional
    public Objection file(Long applicationId, Long candidateId,
                          Long reasonId, String argument) {
        Application application = findOwned(applicationId, candidateId);
        Session session = sessionRepository.findById(application.getSessionId()).orElseThrow();

        String blocked = whyNot(application, session,
                objectionRepository.existsByApplicationId(applicationId));
        if (blocked != null) {
            throw new ObjectionNotAllowedException(blocked);
        }

        ObjectionReason reason = reasonRepository.findById(reasonId)
                .filter(ObjectionReason::isActive)
                .orElseThrow(() -> new ObjectionNotAllowedException(
                        "Motif de réclamation invalide."));

        if (argument == null || argument.trim().length() < MIN_ARGUMENT_LENGTH) {
            throw new ObjectionNotAllowedException(
                    "Exposez votre contestation en %d caractères au minimum. Le membre "
                  + "de la commission qui réexaminera votre dossier doit comprendre ce "
                  + "que vous contestez."
                            .formatted(MIN_ARGUMENT_LENGTH));
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

        applicationService.transition(application, ApplicationStatus.UNDER_RECLAMATION,
                candidateId, "Réclamation déposée : " + reason.getLabelFr());

        // The file returns to the pool — for everyone EXCEPT its rejecter.
        application.setClaimedBy(null);
        application.setClaimedAt(null);
        applicationRepository.save(application);

        emailService.sendObjectionReceived(candidateId, applicationId, reason.getLabelFr());

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
                    "Votre réclamation ne peut pas être enregistrée pour le moment : "
                  + "aucun autre membre de la commission n'est disponible pour la "
                  + "réexaminer. Contactez la HAPA.");
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
