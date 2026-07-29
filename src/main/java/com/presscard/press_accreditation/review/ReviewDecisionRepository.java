package com.presscard.press_accreditation.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, Long> {

    /** Whether a reviewer has any history — governs the two-tier delete. */
    boolean existsByReviewerId(Long reviewerId);

    /** The full decision history of one application, oldest first. */
    List<ReviewDecision> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);

    /** One decision per round — the DB enforces it, this reads it. */
    Optional<ReviewDecision> findByApplicationIdAndRound(Long applicationId, ReviewRound round);

    boolean existsByApplicationIdAndRound(Long applicationId, ReviewRound round);

    /**
     * Week 5: the reclamation must be examined by a DIFFERENT reviewer than
     * the one who rejected (V1.3 §J). This finds who that was.
     */
    Optional<ReviewDecision> findByApplicationIdAndDecision(Long applicationId, DecisionType decision);

    /** A reviewer's output — for the admin's activity view. */
    long countByReviewerId(Long reviewerId);

    /** Which applications this reviewer has decided, any round. */
    @Query("""
           SELECT DISTINCT d.applicationId FROM ReviewDecision d
           WHERE d.reviewerId = :reviewerId
           """)
    List<Long> findApplicationIdsDecidedBy(@Param("reviewerId") Long reviewerId);

    /** This reviewer's decisions on one application, newest first. */
    List<ReviewDecision> findByApplicationIdAndReviewerIdOrderByCreatedAtDesc(
            Long applicationId, Long reviewerId);
}
