package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.review.*;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The dashed edge of the workflow: a correction request that goes unanswered
 * becomes a rejection.
 *
 * WHY A NIGHTLY SWEEP rather than a timer per application: one job, one log
 * line per night, and every rejection it produces is explainable by pointing
 * at a single run. Per-application timers would scatter the same decision
 * across thousands of scheduled tasks that nobody can audit.
 *
 * THREE PROPERTIES THAT MATTER FOR A REGULATOR.
 *
 * 1. THE CANDIDATE IS WARNED FIRST. Forty-eight hours out, one e-mail. A
 *    deadline nobody was told about is a trap, not a rule — and this
 *    rejection would otherwise arrive with no notice at all.
 *
 * 2. THE JUSTIFICATION IS THE UNANSWERED REQUEST. Not "délai expiré", which
 *    tells the candidate nothing they can contest, but the actual correction
 *    they were asked for and did not supply.
 *
 * 3. THE DECISION HAS AN AUTHOR. review_decisions.reviewer_id is NOT NULL,
 *    and rightly: someone answers for every decision. The rejection is
 *    attributed to THE REVIEWER WHO REQUESTED THE CORRECTION, because it is
 *    the direct consequence of their request. Attributing it to a system
 *    account would put an unanswerable decision in the audit trail.
 *
 * The objection right still applies — this rejection can be contested like
 * any other (V1.3 §J and the workflow diagram).
 */
@Component
public class CorrectionDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger("CORRECTION_DEADLINE");

    /** How long before the deadline the warning goes out. */
    private static final int WARNING_DAYS = 2;

    private final ApplicationRepository applicationRepository;
    private final SessionRepository sessionRepository;
    private final ReviewDecisionRepository decisionRepository;
    private final ApplicationService applicationService;
    private final EmailService emailService;

    public CorrectionDeadlineJob(ApplicationRepository applicationRepository,
                                 SessionRepository sessionRepository,
                                 ReviewDecisionRepository decisionRepository,
                                 ApplicationService applicationService,
                                 EmailService emailService) {
        this.applicationRepository = applicationRepository;
        this.sessionRepository = sessionRepository;
        this.decisionRepository = decisionRepository;
        this.applicationService = applicationService;
        this.emailService = emailService;
    }

    /**
     * 02:30 nightly — before the working day, after the previous one is over,
     * and not on the hour where every other scheduled job in the building
     * runs.
     */
    @Scheduled(cron = "0 30 2 * * *")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now();
        warnUpcoming(today);
        rejectExpired(today);
    }

    /* ══ the warning ══════════════════════════════════════════ */

    private void warnUpcoming(LocalDate today) {
        LocalDate warnFor = today.plusDays(WARNING_DAYS);

        List<Session> closingSoon = sessionRepository.findByCorrectionEnd(warnFor);
        if (closingSoon.isEmpty()) {
            return;
        }

        int sent = 0;
        for (Session session : closingSoon) {
            List<Application> awaiting = applicationRepository
                    .findAwaitingCorrection(session.getId());

            for (Application application : awaiting) {
                // Recorded, so a restarted job never warns the same person twice.
                if (application.getCorrectionWarningSentAt() != null) {
                    continue;
                }
                emailService.sendCorrectionDeadlineWarning(
                        application.getCandidateId(),
                        application.getId(),
                        session.getCorrectionEnd(),
                        WARNING_DAYS);

                application.setCorrectionWarningSentAt(OffsetDateTime.now());
                applicationRepository.save(application);
                sent++;
            }
        }

        if (sent > 0) {
            log.info("CORRECTION_WARNINGS_SENT count={} deadline={}", sent, warnFor);
        }
    }

    /**
     * Reject every unanswered correction in one session.
     *
     * Called from TWO places: the nightly sweep when correction_end has
     * passed, and SessionService.advancePhase when an administrator closes
     * the correction phase early. Whichever comes first ends the round.
     *
     * @return how many were rejected — the caller logs it in its own terms
     */
    @Transactional
    public int rejectUnansweredIn(Session session) {
        List<Application> unanswered = applicationRepository
                .findAwaitingCorrection(session.getId());

        for (Application application : unanswered) {
            rejectOne(application, session);
        }
        return unanswered.size();
    }

    /* ══ the rejection ════════════════════════════════════════ */

//    private void rejectExpired(LocalDate today) {
//        List<Session> expired = sessionRepository.findByCorrectionEndBefore(today);
//        if (expired.isEmpty()) {
//            return;
//        }
//
//        int rejected = 0;
//        for (Session session : expired) {
//            List<Application> unanswered = applicationRepository
//                    .findAwaitingCorrection(session.getId());
//
//            for (Application application : unanswered) {
//                rejectOne(application, session);
//                rejected++;
//            }
//        }
//
//        if (rejected > 0) {
//            log.warn("CORRECTION_DEADLINE_REJECTIONS count={} date={}", rejected, today);
//        }
//    }

    private void rejectExpired(LocalDate today) {
        List<Session> expired = sessionRepository.findByCorrectionEndBefore(today);
        int rejected = 0;

        for (Session session : expired) {
            rejected += rejectUnansweredIn(session);
        }
        if (rejected > 0) {
            log.warn("CORRECTION_DEADLINE_REJECTIONS count={} date={}", rejected, today);
        }
    }

    private void rejectOne(Application application, Session session) {
        // The correction that was asked for, and never answered. This is the
        // justification the candidate reads and may contest — "délai expiré"
        // would tell them nothing they could argue with.
        ReviewDecision request = decisionRepository
                .findByApplicationIdAndRound(application.getId(), ReviewRound.INITIAL)
                .filter(d -> d.getDecision() == DecisionType.REQUEST_CORRECTION)
                .orElse(null);

        String originalRequest = request == null
                ? "Des corrections avaient été demandées."
                : request.getJustification();

        String justification = """
                Corrections demandées et non déposées dans le délai imparti \
                (échéance : %s).

                Demande de la commission restée sans réponse :
                %s"""
                .formatted(session.getCorrectionEnd(), originalRequest);

        // Property 3: the decision is attributed to the reviewer whose request
        // went unanswered — it is the direct consequence of that request, and
        // every decision in the audit trail must have someone who answers for it.
        Long author = request == null ? null : request.getReviewerId();
        if (author == null) {
            // No traceable request: leave the file alone rather than record a
            // decision nobody can account for. It will surface in the logs.
            log.error("CORRECTION_DEADLINE_NO_AUTHOR application={} — left untouched",
                    application.getId());
            return;
        }

        decisionRepository.save(ReviewDecision.builder()
                .applicationId(application.getId())
                .reviewerId(author)
                .decision(DecisionType.REJECT)
                .rejectionGround(RejectionGround.INCOMPLETE_FILE)
                .justification(justification)
                .round(ReviewRound.FINAL)
                .build());

        applicationService.transition(application, ApplicationStatus.REJECTED,
                author, justification);

        // The claim, if any, ends with the decision.
        application.setClaimedBy(null);
        application.setClaimedAt(null);
        applicationRepository.save(application);

        emailService.sendDecisionNotice(application.getCandidateId(),
                application.getId(), DecisionType.REJECT.name(), justification);

        log.warn("CORRECTION_DEADLINE_REJECTED application={} attributedTo={} deadline={}",
                application.getId(), author, session.getCorrectionEnd());
    }
}
