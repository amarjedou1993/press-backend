package com.presscard.press_accreditation.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * The calendar keeps itself.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ WHY THIS EXISTS: A PUBLISHED DEADLINE MUST BE TRUE.
 *
 * Until now a phase advanced only when an administrator clicked. Which meant
 * the real deadline was "whenever somebody remembers" — the 17th if they were
 * busy, the 14th if they were early — while the public page told candidates
 * the 15th.
 *
 * A candidate plans around that date. Making it advisory is a small
 * dishonesty that costs somebody their application.
 * ───────────────────────────────────────────────────────────────────────
 *
 * MANUAL ADVANCE REMAINS, and is not a fallback. Closing a phase early when
 * every dossier is decided is a legitimate act: the schedule is a floor, not
 * a rule. This job only ensures the ceiling holds.
 *
 * 02:00 — before the correction sweep at 02:30, deliberately. Leaving the
 * correction phase rejects unanswered dossiers through SessionService, so
 * this must run first and let that path own the decisions. The 02:30 sweep
 * then finds nothing, and remains a net for sessions this job could not
 * advance.
 */
@Component
public class SessionPhaseJob {

    private static final Logger log = LoggerFactory.getLogger("SESSION_PHASE_JOB");

    private final SessionRepository repository;
    private final SessionService sessionService;

    public SessionPhaseJob(SessionRepository repository, SessionService sessionService) {
        this.repository = repository;
        this.sessionService = sessionService;
    }

    /**
     * ⚠️ FROM CONFIGURATION, with a default.
     *
     * CorrectionDeadlineJob hard-codes its cron while AppProperties carries a
     * setting for it — a divergence worth not repeating. The default is here
     * so an absent key cannot silently stop the calendar.
     */
    @Scheduled(cron = "${app.session.auto-advance-cron:0 0 2 * * *}")
    public void run() {
        LocalDate today = LocalDate.now();

        openDueSessions(today);
        advanceExpiredPhases(today);
    }

    /**
     * A PLANNED session whose start date has arrived opens.
     *
     * ⚠️ Handled separately because PLANNED has no phase end — currentPhaseEnd()
     * returns null for it. Its boundary is its start date, which is a
     * different question with the same answer.
     */
    private void openDueSessions(LocalDate today) {
        List<Session> due = repository.findByStatusAndStartDateLessThanEqual(
                SessionStatus.PLANNED, today);

        for (Session session : due) {
            try {
                sessionService.advancePhaseAutomatically(session, session.getStartDate());
                log.info("SESSION_AUTO_OPENED id={} start={}",
                        session.getId(), session.getStartDate());
            } catch (RuntimeException e) {
                // Named, never swallowed: a session that failed to open is a
                // public page saying "closed" while the calendar says open.
                log.error("SESSION_AUTO_OPEN_FAILED id={} reason={}",
                        session.getId(), e.getMessage(), e);
            }
        }
    }

    /** Every active phase whose end date has passed moves on. */
    private void advanceExpiredPhases(LocalDate today) {
        for (SessionStatus phase : List.of(
                SessionStatus.RECEIVING, SessionStatus.REVIEW,
                SessionStatus.CORRECTION, SessionStatus.RECLAMATION)) {

            for (Session session : repository.findByStatus(phase)) {
                LocalDate end = session.currentPhaseEnd();
                if (end == null || !today.isAfter(end)) {
                    continue;   // still inside its allotted days
                }
                try {
                    /*
                     * ⚠️ CLOSED AT THE PUBLISHED DATE, not at today.
                     *
                     * A manual advance stamps the outgoing phase with today,
                     * because an administrator closing early IS making today
                     * the end. An automatic one is different: the phase ended
                     * on the date everyone was told, and this job is merely
                     * noticing. Stamping today would rewrite a published date
                     * — by a day if the job ran on time, by more if the server
                     * was down over a weekend.
                     *
                     * It also keeps the downstream calendar intact: the next
                     * phase is anchored at the published boundary, so a late
                     * job does not push the whole session forward.
                     */
                    sessionService.advancePhaseAutomatically(session, end);
                    log.info("SESSION_AUTO_ADVANCED id={} {}->{} at={}",
                            session.getId(), phase,
                            phase.next().map(Enum::name).orElse("CLOSED"), end);
                } catch (RuntimeException e) {
                    log.error("SESSION_AUTO_ADVANCE_FAILED id={} phase={} reason={}",
                            session.getId(), phase, e.getMessage(), e);
                }
            }
        }
    }
}
