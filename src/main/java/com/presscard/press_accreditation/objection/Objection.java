package com.presscard.press_accreditation.objection;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A candidate's contestation of a rejection.
 *
 * ONE PER APPLICATION, enforced by a UNIQUE constraint rather than by
 * application code — a right that could be exercised twice could be exercised
 * indefinitely, and the database is where that guarantee belongs.
 *
 * The contested decision is PINNED at creation, so the record stays
 * unambiguous after the reclamation produces a decision of its own.
 */
@Entity
@Table(name = "objections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Objection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, unique = true)
    private Long applicationId;

    @Column(name = "reason_id", nullable = false)
    private Long reasonId;

    /** What the candidate disputes, in their own words. */
    @Column(nullable = false, columnDefinition = "text")
    private String argument;

    @Column(name = "contested_decision_id")
    private Long contestedDecisionId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
