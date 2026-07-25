package com.presscard.press_accreditation.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    /** The one application a candidate may have in a given session. */
    Optional<Application> findByCandidateIdAndSessionId(Long candidateId, Long sessionId);

    boolean existsByCandidateIdAndSessionId(Long candidateId, Long sessionId);

    /** A candidate's history across sessions, most recent first. */
    List<Application> findByCandidateIdOrderByCreatedAtDesc(Long candidateId);

    /** Reviewer pool (week 4): unclaimed files awaiting review in a session. */
    List<Application> findBySessionIdAndStatusAndClaimedByIsNull(
            Long sessionId, ApplicationStatus status);

    /** Admin results page: counts per status for a session. */
    long countBySessionIdAndStatus(Long sessionId, ApplicationStatus status);

    long countBySessionId(Long sessionId);
}
