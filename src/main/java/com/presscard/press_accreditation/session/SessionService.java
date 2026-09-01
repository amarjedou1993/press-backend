package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.application.CorrectionDeadlineJob;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.error.InvalidPhaseTransitionException;
import com.presscard.press_accreditation.error.SessionNotFoundException;
import com.presscard.press_accreditation.error.SessionTooCloseException;
import com.presscard.press_accreditation.session.SessionDtos.CreateSessionRequest;
import com.presscard.press_accreditation.session.SessionDtos.SessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Session lifecycle.
 *
 * OPTION A — durations are guaranteed, the calendar floats.
 * A phase that opens receives its FULL allotted number of days counted from
 * the day it opens; every downstream boundary shifts with it. The commission
 * never loses examination time because an admin clicked early; the session
 * simply finishes sooner.
 *
 * A transition therefore does THREE things, and all three matter:
 *   1. CLOSE the outgoing phase — its end date becomes the actual close date,
 *      replacing the forecast. (Omitting this leaves a stale forecast that can
 *      sit AFTER the newly re-based boundaries — the DB rejected exactly that.)
 *   2. If RECEIVING is opening, move start_date to today: the session really
 *      begins when it starts accepting candidates.
 *   3. RE-FORECAST every later boundary from today.
 *
 * After a transition, past boundaries are HISTORY (what happened) and future
 * ones are FORECAST (what is planned) — one field serving both roles as time
 * passes through it.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger("SESSION_AUDIT");

    private final SessionRepository repository;
    private final PublicCacheNotifier cacheNotifier;
    private final AppProperties props;
    private final CorrectionDeadlineJob correctionDeadlineJob;
    private final ApplicationRepository applicationRepository;

    public SessionService(SessionRepository repository,
                          PublicCacheNotifier cacheNotifier,
                          AppProperties props,
                          CorrectionDeadlineJob correctionDeadlineJob,
                          ApplicationRepository applicationRepository) {   // ← ADD
        this.repository = repository;
        this.cacheNotifier = cacheNotifier;
        this.props = props;
        this.correctionDeadlineJob = correctionDeadlineJob;
        this.applicationRepository = applicationRepository;               // ← ADD
    }

    @Transactional
    public SessionResponse create(CreateSessionRequest req, Long adminId) {
        int gapDays = props.session().minimumGapDays();
        if (gapDays > 0) {
            repository.findTopByOrderByStartDateDesc().ifPresent(previous -> {
                LocalDate earliest = previous.getStartDate().plusDays(gapDays);
                if (req.startDate().isBefore(earliest)) {
                    throw new SessionTooCloseException(
                            ("Une session a déjà débuté le %s. La prochaine ne peut pas "
                                    + "commencer avant le %s (%d jours d'intervalle).")
                                    .formatted(
                                            formatFr(previous.getStartDate()),
                                            formatFr(earliest),
                                            gapDays));
                }
            });
        }
        LocalDate start = req.startDate();

        Session session = Session.builder()
                .type(SessionType.CANDIDACY)
                .startDate(start)
                .totalDays(req.totalDays())
                .receivingDays(req.receivingDays())
                .reviewDays(req.reviewDays())
                .correctionDays(req.correctionDays())
                .reclamationDays(req.reclamationDays())
                .status(SessionStatus.PLANNED)
                .phaseStartedAt(start)
                .createdBy(adminId)
                .cardExpiryDate(req.cardExpiryDate())
                .build();

        forecastFrom(session, SessionStatus.RECEIVING, start);

        // AFTER forecastFrom: reclamationEnd does not exist until the phases
        // are laid out, so this cannot sit with the validation above.
        //
        // A card that lapses before the session that granted it is absurd, and
        // it is exactly the mistake a date picker makes easy. The DB CHECK
        // backs this up — but a constraint violation surfaces a constraint
        // NAME, and an administrator deserves a sentence.
        if (session.getCardExpiryDate() != null
                && !session.getCardExpiryDate().isAfter(session.getReclamationEnd())) {
            throw new InvalidPhaseTransitionException(
                    ("La date d'expiration des cartes (%s) doit être postérieure à la "
                            + "fin de la session (%s).")
                            .formatted(
                                    formatFr(session.getCardExpiryDate()),
                                    formatFr(session.getReclamationEnd())));
        }

        session = repository.save(session);
        log.info("SESSION_CREATED id={} start={} days={}/{}/{}/{} cardExpiry={} by={}",
                session.getId(), start, req.receivingDays(), req.reviewDays(),
                req.correctionDays(), req.reclamationDays(),
                session.getCardExpiryDate(), adminId);

        cacheNotifier.notifySessionsChanged();
        return SessionResponse.of(session);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listAll() {
        return repository.findAllByOrderByStartDateDesc().stream()
                .map(s -> SessionResponse.of(s, awaitingCorrectionCount(s)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SessionResponse get(Long id) {
        Session session = find(id);
        return SessionResponse.of(session, awaitingCorrectionCount(session));
    }

    /**
     * Advance a phase because its date has passed, not because someone clicked.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ THE DIFFERENCE FROM advancePhase IS THE CLOSING DATE, and it is not
     * cosmetic.
     *
     * A manual advance stamps the outgoing phase with TODAY, and rightly: an
     * administrator closing early is making today the end, and the downstream
     * calendar shifts earlier with it. That is Option A.
     *
     * An automatic advance is the opposite case. The phase ended on the date
     * that was published, and this is merely the system noticing. Stamping
     * today would rewrite a date candidates planned around — by one day if
     * the job ran on schedule, by three if the server was down over a
     * weekend.
     *
     * So the boundary is passed in, the outgoing phase is closed AT it, and
     * the incoming phase is anchored THERE. A late job therefore does not
     * push the session forward: the calendar it publishes is the calendar it
     * keeps.
     * ───────────────────────────────────────────────────────────────────
     *
     * ⚠️ AND THE ACTOR IS NULL, deliberately.
     *
     * adminId is used for the audit line only. There is no administrator
     * here — nobody decided this, the calendar did — and naming one would put
     * a decision in the trail against a person who was not at their desk.
     */
    @Transactional
    public SessionResponse advancePhaseAutomatically(Session session, LocalDate boundary) {
        SessionStatus from = session.getStatus();
        SessionStatus to = from.next().orElseThrow(() ->
                new InvalidPhaseTransitionException(
                        "Session " + session.getId() + " is already CLOSED."));

        // The outgoing phase ended on its published date.
        closePhase(session, from, boundary);

        // Leaving CORRECTION ends the correction round — the same sweep the
        // manual path performs, so the rejections are recorded once and
        // through one route.
        if (from == SessionStatus.CORRECTION) {
            int swept = correctionDeadlineJob.rejectUnansweredIn(session);
            if (swept > 0) {
                log.warn("SESSION_AUTO_ADVANCE_SWEPT session={} rejected={} "
                                + "— corrections unanswered when the phase closed",
                        session.getId(), swept);
            }
        }

        // ⚠️ start_date is NOT moved, unlike the manual path. A session that
        // opens on its own opens on the day it was announced for; moving it
        // would contradict the announcement.

        session.setStatus(to);
        session.setPhaseStartedAt(boundary);
        forecastFrom(session, to, boundary);

        repository.save(session);
        log.info("SESSION_PHASE_AUTO id={} {}->{} at={} sessionEnd={}",
                session.getId(), from, to, boundary, session.getReclamationEnd());
        cacheNotifier.notifySessionsChanged();
        return SessionResponse.of(session);
    }

    /**
     * The constraints on opening a new session, so the UI can present them
     * rather than let the admin discover them by being refused.
     */
    @Transactional(readOnly = true)
    public SessionDtos.SessionSchedulingRules schedulingRules() {
        int gapDays = props.session().minimumGapDays();
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        return repository.findTopByOrderByStartDateDesc()
                .map(previous -> {
                    LocalDate earliest = gapDays > 0
                            ? previous.getStartDate().plusDays(gapDays)
                            : tomorrow;
                    // A session always starts in the future, whatever the gap.
                    if (earliest.isBefore(tomorrow)) earliest = tomorrow;
                    return new SessionDtos.SessionSchedulingRules(
                            gapDays, previous.getStartDate(), earliest);
                })
                .orElseGet(() -> new SessionDtos.SessionSchedulingRules(
                        gapDays, null, tomorrow));
    }

    @Transactional
    public SessionResponse advancePhase(Long id, Long adminId) {
        Session session = find(id);
        SessionStatus from = session.getStatus();
        SessionStatus to = from.next().orElseThrow(() ->
                new InvalidPhaseTransitionException(
                        "Session " + id + " is already CLOSED; no further phase."));

        LocalDate today = LocalDate.now();

        // 1. The outgoing phase ended today — record it as fact, not forecast.
        closePhase(session, from, today);

        // 1b. Leaving the correction phase ENDS the correction round, whatever
        //     the calendar says. Swept here, while the session is still in
        //     CORRECTION, so each rejection is recorded against the phase that
        //     produced it.
        if (from == SessionStatus.CORRECTION) {
            int swept = correctionDeadlineJob.rejectUnansweredIn(session);
            if (swept > 0) {
                log.warn("SESSION_PHASE_ADVANCE_SWEPT session={} rejected={} "
                                + "— corrections unanswered when the phase was closed",
                        session.getId(), swept);
            }
        }

        // 2. The session truly starts when it starts receiving candidatures.
        if (to == SessionStatus.RECEIVING) {
            session.setStartDate(today);
        }

        // 3. Open the new phase and re-forecast everything after it.
        session.setStatus(to);
        session.setPhaseStartedAt(today);
        forecastFrom(session, to, today);

        repository.save(session);
        log.info("SESSION_PHASE id={} {}->{} on={} sessionEnd={} by={}",
                id, from, to, today, session.getReclamationEnd(), adminId);
        cacheNotifier.notifySessionsChanged();
        return SessionResponse.of(session);
    }

    /** Stamp the actual end date of a phase that is closing. */
    private void closePhase(Session s, SessionStatus phase, LocalDate on) {
        switch (phase) {
            case RECEIVING -> s.setReceivingEnd(on);
            case REVIEW -> s.setReviewEnd(on);
            case CORRECTION -> s.setCorrectionEnd(on);
            case RECLAMATION -> s.setReclamationEnd(on);
            default -> { /* PLANNED / CLOSED are not timed phases */ }
        }
    }

    /**
     * Re-derive the boundaries of `phase` and everything after it, giving each
     * its full allotted duration counted from `anchor`. Earlier boundaries are
     * left alone — they are history now.
     */
    private void forecastFrom(Session s, SessionStatus phase, LocalDate anchor) {
        LocalDate cursor = anchor;

        switch (phase) {
            case RECEIVING -> {
                cursor = cursor.plusDays(s.getReceivingDays());
                s.setReceivingEnd(cursor);
                cursor = cursor.plusDays(s.getReviewDays());
                s.setReviewEnd(cursor);
                cursor = cursor.plusDays(s.getCorrectionDays());
                s.setCorrectionEnd(cursor);
                cursor = cursor.plusDays(s.getReclamationDays());
                s.setReclamationEnd(cursor);
            }
            case REVIEW -> {
                cursor = cursor.plusDays(s.getReviewDays());
                s.setReviewEnd(cursor);
                cursor = cursor.plusDays(s.getCorrectionDays());
                s.setCorrectionEnd(cursor);
                cursor = cursor.plusDays(s.getReclamationDays());
                s.setReclamationEnd(cursor);
            }
            case CORRECTION -> {
                cursor = cursor.plusDays(s.getCorrectionDays());
                s.setCorrectionEnd(cursor);
                cursor = cursor.plusDays(s.getReclamationDays());
                s.setReclamationEnd(cursor);
            }
            case RECLAMATION -> {
                cursor = cursor.plusDays(s.getReclamationDays());
                s.setReclamationEnd(cursor);
            }
            default -> { /* PLANNED keeps creation dates; CLOSED was stamped by closePhase */ }
        }
    }

    private Session find(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new SessionNotFoundException(id));
    }

    /**
     * Dossiers still awaiting their candidate's corrections.
     *
     * ONLY during CORRECTION. Outside that phase nothing is awaiting an
     * answer, and the sessions list loads on every visit to the admin space —
     * a query per session to learn it is zero is a query for nothing.
     *
     * This figure exists for one screen: the phase-advance confirmation.
     * Leaving CORRECTION rejects every one of these automatically, and the
     * administrator should be told the number before they agree, not after.
     */
    private long awaitingCorrectionCount(Session session) {
        return session.getStatus() == SessionStatus.CORRECTION
                ? applicationRepository.findAwaitingCorrection(session.getId()).size()
                : 0L;
    }

    private static String formatFr(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter
                .ofPattern("d MMMM yyyy", java.util.Locale.FRENCH));
    }
}
