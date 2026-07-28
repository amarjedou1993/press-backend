package com.presscard.press_accreditation.review;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * An immutable record of one decision. Never updated, never deleted — for a
 * regulator, the audit trail IS the product: "who decided this accreditation,
 * when, and on what ground" must remain answerable years later.
 *
 * The database guarantees:
 *  · UNIQUE (application_id, round) — one decision per round
 *  · a REJECT carries a ground; anything else carries none
 *  · reviewer_id NOT NULL — every decision has an author who answers for it
 */
@Entity
@Table(name = "review_decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DecisionType decision;

    /** Mandatory on REJECT and REQUEST_CORRECTION; the candidate reads this. */
    @Column(columnDefinition = "text")
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_ground", length = 30)
    private RejectionGround rejectionGround;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewRound round;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
