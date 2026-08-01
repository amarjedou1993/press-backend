package com.presscard.press_accreditation.session;

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

    public SessionService(SessionRepository repository, PublicCacheNotifier cacheNotifier, AppProperties props, CorrectionDeadlineJob correctionDeadlineJob ) {
        this.repository = repository;
        this.cacheNotifier = cacheNotifier;
        this.props = props;
        this.correctionDeadlineJob = correctionDeadlineJob;
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
                .build();

        forecastFrom(session, SessionStatus.RECEIVING, start);

        session = repository.save(session);
        log.info("SESSION_CREATED id={} start={} days={}/{}/{}/{} by={}",
                session.getId(), start, req.receivingDays(), req.reviewDays(),
                req.correctionDays(), req.reclamationDays(), adminId);

        cacheNotifier.notifySessionsChanged();
        return SessionResponse.of(session);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listAll() {
        return repository.findAllByOrderByStartDateDesc().stream()
                .map(SessionResponse::of).toList();
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

    @Transactional(readOnly = true)
    public SessionResponse get(Long id) {
        return SessionResponse.of(find(id));
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

    private static String formatFr(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter
                .ofPattern("d MMMM yyyy", java.util.Locale.FRENCH));
    }
}
