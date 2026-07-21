package com.presscard.press_accreditation.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findAllByOrderByStartDateDesc();

    /** Public page: sessions currently accepting candidates. */
    List<Session> findByStatusOrderByStartDateDesc(SessionStatus status);
}
