package com.presscard.press_accreditation.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findAllByOrderByStartDateDesc();

    /** Public page: sessions currently accepting candidates. */
    List<Session> findByStatusOrderByStartDateDesc(SessionStatus status);

    /** The most recent session by start date — for the spacing rule. */
    Optional<Session> findTopByOrderByStartDateDesc();
}
