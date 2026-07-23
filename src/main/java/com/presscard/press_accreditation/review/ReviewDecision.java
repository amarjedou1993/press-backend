package com.presscard.press_accreditation.review;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Immutable audit row: one reviewer decision on one application
 * (V1.3 §G). Maps to the review_decisions table from V1__init.sql.
 *
 * Week-2 scope is minimal — only the fields needed to check a reviewer's
 * history for the deletion rule. The full decision workflow (approve /
 * reject / request-correction, rounds, the different-reviewer trigger)
 * is wired in week 4.
 */
@Entity
@Table(name = "review_decisions")
@Getter
public class ReviewDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(nullable = false, length = 30)
    private String decision;

    private String justification;

    @Column(nullable = false, length = 20)
    private String round;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
