package com.presscard.press_accreditation.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findAllByOrderByStartDateDesc();

    /** Public page: sessions currently accepting candidates. */
    List<Session> findByStatusOrderByStartDateDesc(SessionStatus status);

    /** The most recent session by start date — for the spacing rule. */
    Optional<Session> findTopByOrderByStartDateDesc();

    /** Sessions whose correction phase ends on exactly this date. */
    List<Session> findByCorrectionEnd(LocalDate date);

    /** Sessions whose correction phase has already closed. */
    List<Session> findByCorrectionEndBefore(LocalDate date);

    /** Every session in one phase. */
    List<Session> findByStatus(SessionStatus status);

    /** Planned sessions whose start date has arrived. */
    List<Session> findByStatusAndStartDateLessThanEqual(
            SessionStatus status, LocalDate date);

}
