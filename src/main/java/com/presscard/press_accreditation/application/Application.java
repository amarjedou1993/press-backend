package com.presscard.press_accreditation.application;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A candidate's request for a press card in a given session.
 * Maps 1:1 to the applications table (V1__init.sql).
 *
 * Invariants the DATABASE guarantees, so this class need not re-check them:
 *  · one application per candidate per session (UNIQUE)
 *  · correction_count between 0 and 1 (V1.3 §H — one correction round)
 *  · status ∈ the nine states (CHECK)
 */
@Entity
@Table(name = "applications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    /** Reviewer lock: who is currently examining this file (week 4). */
    @Column(name = "claimed_by")
    private Long claimedBy;

    @Column(name = "correction_count", nullable = false)
    @Builder.Default
    private int correctionCount = 0;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** When the current reviewer claimed it — drives the stale-claim release. */
    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    /**
     * The photograph was judged unusable for a credential. It is not a row in
     * application_documents (it belongs to the person, not the application),
     * so its correction flag lives here.
     */
    @Column(name = "photo_needs_correction", nullable = false)
    @Builder.Default
    private boolean photoNeedsCorrection = false;

    @Column(name = "photo_observation", columnDefinition = "text")
    private String photoObservation;

    /** When the 48-hour reminder went out. Prevents a repeat on re-run. */
    @Column(name = "correction_warning_sent_at")
    private OffsetDateTime correctionWarningSentAt;

    /** When the commission asked for corrections — the clock's start. */
    @Column(name = "correction_requested_at")
    private OffsetDateTime correctionRequestedAt;
}
