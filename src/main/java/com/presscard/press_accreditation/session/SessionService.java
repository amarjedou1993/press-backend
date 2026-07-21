package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.error.InvalidPhaseTransitionException;
import com.presscard.press_accreditation.session.SessionDtos.CreateSessionRequest;
import com.presscard.press_accreditation.session.SessionDtos.SessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Session lifecycle. Two operations carry all the weight:
 *  - create: derive the four boundary dates from start + per-phase days.
 *  - advancePhase: the ONLY way status moves, always +1 step, always audited.
 *    No automatic date-based transitions (V1.3 §F).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger("SESSION_AUDIT");

    private final SessionRepository repository;

    public SessionService(SessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SessionResponse create(CreateSessionRequest req, Long adminId) {
        LocalDate start = req.startDate();
        LocalDate receivingEnd = start.plusDays(req.receivingDays());
        LocalDate reviewEnd = receivingEnd.plusDays(req.reviewDays());
        LocalDate correctionEnd = reviewEnd.plusDays(req.correctionDays());
        LocalDate reclamationEnd = correctionEnd.plusDays(req.reclamationDays());

        Session session = Session.builder()
                .type(SessionType.CANDIDACY)
                .startDate(start)
                .totalDays(req.totalDays())
                .receivingEnd(receivingEnd)
                .reviewEnd(reviewEnd)
                .correctionEnd(correctionEnd)
                .reclamationEnd(reclamationEnd)
                .status(SessionStatus.PLANNED)
                .createdBy(adminId)
                .build();

        session = repository.save(session);
        log.info("SESSION_CREATED id={} start={} totalDays={} by={}",
                session.getId(), start, req.totalDays(), adminId);
        return SessionResponse.of(session);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listAll() {
        return repository.findAllByOrderByStartDateDesc().stream()
                .map(SessionResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public SessionResponse get(Long id) {
        return SessionResponse.of(find(id));
    }

    /**
     * Advance to the next phase. Rejects advancing a CLOSED session — the
     * only illegal move in a linear machine.
     */
    @Transactional
    public SessionResponse advancePhase(Long id, Long adminId) {
        Session session = find(id);
        SessionStatus from = session.getStatus();
        SessionStatus to = from.next().orElseThrow(() ->
                new InvalidPhaseTransitionException(
                        "Session " + id + " is already CLOSED; no further phase."));

        session.setStatus(to);
        repository.save(session);
        log.info("SESSION_PHASE id={} {}->{} by={}", id, from, to, adminId);
        return SessionResponse.of(session);
    }

    private Session find(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new SessionNotFoundException(id));
    }
}
