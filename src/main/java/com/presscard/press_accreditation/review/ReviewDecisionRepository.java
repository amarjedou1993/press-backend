//package com.presscard.press_accreditation.review;
//
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//
//import java.util.List;
//import java.util.Optional;
//
//public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, Long> {
//
//    /** Whether a reviewer has any history — governs the two-tier delete. */
//    boolean existsByReviewerId(Long reviewerId);
//
//    /** The full decision history of one application, oldest first. */
//    List<ReviewDecision> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);
//
//    /** One decision per round — the DB enforces it, this reads it. */
//    Optional<ReviewDecision> findByApplicationIdAndRound(Long applicationId, ReviewRound round);
//
//    boolean existsByApplicationIdAndRound(Long applicationId, ReviewRound round);
//
//    /**
//     * Week 5: the reclamation must be examined by a DIFFERENT reviewer than
//     * the one who rejected (V1.3 §J). This finds who that was.
//     */
//    Optional<ReviewDecision> findByApplicationIdAndDecision(Long applicationId, DecisionType decision);
//
//    /** A reviewer's output — for the admin's activity view. */
//    long countByReviewerId(Long reviewerId);
//
//    /** Which applications this reviewer has decided, any round. */
//    @Query("""
//           SELECT DISTINCT d.applicationId FROM ReviewDecision d
//           WHERE d.reviewerId = :reviewerId
//           """)
//    List<Long> findApplicationIdsDecidedBy(@Param("reviewerId") Long reviewerId);
//
//    /** This reviewer's decisions on one application, newest first. */
//    List<ReviewDecision> findByApplicationIdAndReviewerIdOrderByCreatedAtDesc(
//            Long applicationId, Long reviewerId);
//}

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

    /**
     * One reviewer's latest decision on each of a set of applications.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ ONE QUERY FOR A WHOLE PAGE, replacing one per row.
     *
     * The method above is the single-application form, and calling it inside
     * a list mapping is what put the pool listing at four queries per dossier
     * — about a hundred sequential round trips for a page of twenty-four, all
     * on one pooled connection.
     *
     * ⚠️ NATIVE, AND POSTGRESQL-SPECIFIC.
     *
     * DISTINCT ON is Postgres's way of saying "the first row of each group" —
     * here the most recent decision per application, given the ORDER BY. JPQL
     * has no equivalent; the portable version is a correlated subquery per
     * row, which is the problem this exists to remove.
     *
     * The database is fixed for this system, so the trade is worth taking —
     * but it is a dependency to know about rather than to discover during a
     * migration.
     * ───────────────────────────────────────────────────────────────────
     */
    @Query(value = """
           SELECT DISTINCT ON (application_id) *
           FROM review_decisions
           WHERE reviewer_id = :reviewerId
             AND application_id IN (:applicationIds)
           ORDER BY application_id, created_at DESC
           """, nativeQuery = true)
    List<ReviewDecision> findLatestByReviewerForApplications(
            @Param("reviewerId") Long reviewerId,
            @Param("applicationIds") List<Long> applicationIds);
}