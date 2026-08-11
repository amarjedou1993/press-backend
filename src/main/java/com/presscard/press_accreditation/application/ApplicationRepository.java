package com.presscard.press_accreditation.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    /** Dossiers in one state — used to list what is awaiting a card. */
    List<Application> findByStatus(ApplicationStatus status);

    /** Every application in a session — the results screen's single fetch. */
    List<Application> findBySessionId(Long sessionId);


    // ── queries ──

    /**
     * The pool: submitted dossiers nobody has taken, oldest first.
     *
     * Oldest-first is deliberate — a candidate who submitted in the first
     * hour should not wait behind one who submitted on the last day.
     */
    @Query("""
           SELECT a FROM Application a
           WHERE a.claimedBy IS NULL
             AND a.status IN ('UNDER_REVIEW', 'UNDER_FINAL_REVIEW', 'UNDER_RECLAMATION')
           ORDER BY a.submittedAt ASC
           """)
    List<Application> findUnclaimedAwaitingReview();

    /** What one reviewer currently holds. */
//    List<Application> findByClaimedByOrderByClaimedAtAsc(Long reviewerId);

    /**
     * Claim ONLY if still unclaimed.
     *
     * The WHERE clause is the concurrency control: two reviewers clicking at
     * the same instant both run this, but only one row is affected, so only
     * one succeeds. Read-then-write would let the second silently overwrite
     * the first — and two members would examine the same file.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE Application a
           SET a.claimedBy = :reviewerId, a.claimedAt = :now
           WHERE a.id = :applicationId AND a.claimedBy IS NULL
           """)
    int claimIfUnclaimed(@Param("applicationId") Long applicationId,
                         @Param("reviewerId") Long reviewerId,
                         @Param("now") OffsetDateTime now);

    /** Stale claims — a reviewer's absence must not freeze a candidate. */
    @Query("""
           SELECT a FROM Application a
           WHERE a.claimedBy IS NOT NULL AND a.claimedAt < :cutoff
           """)
    List<Application> findStaleClaims(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * A reviewer's ACTIVE workload: claimed by them AND still awaiting a
     * decision. The status filter is the fix — without it, decided files
     * never left "Mes dossiers".
     */
    @Query("""
           SELECT a FROM Application a
           WHERE a.claimedBy = :reviewerId
             AND a.status IN ('UNDER_REVIEW', 'UNDER_FINAL_REVIEW', 'UNDER_RECLAMATION')
           ORDER BY a.claimedAt ASC
           """)
    List<Application> findActiveClaimsFor(@Param("reviewerId") Long reviewerId);

    /** Files in a session still awaiting the candidate's corrections. */
    @Query("""
           SELECT a FROM Application a
           WHERE a.sessionId = :sessionId
             AND a.status = 'CORRECTION_REQUESTED'
           """)
    List<Application> findAwaitingCorrection(@Param("sessionId") Long sessionId);

    /**
     * Every dossier the commission may see: anything past DRAFT.
     * Newest submissions first — this is an overview, not a work queue.
     */
    @Query("""
           SELECT a FROM Application a
           WHERE a.status <> 'DRAFT'
           ORDER BY a.submittedAt DESC NULLS LAST
           """)
    List<Application> findAllSubmitted();
}
