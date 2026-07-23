package com.presscard.press_accreditation.review;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Minimal repository over the review_decisions audit table. In week 2 its
 * only job is the history check that governs reviewer deletion; the full
 * review workflow fills this out in week 4.
 */
public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, Long> {

    /** True if this reviewer has ever recorded a decision → cannot hard-delete. */
    boolean existsByReviewerId(Long reviewerId);
}
