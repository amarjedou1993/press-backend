package com.presscard.press_accreditation.session;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A candidacy session (V1.3 §F).
 *
 * Two kinds of date live here and must not be confused:
 *  · the per-phase DURATIONS (receivingDays…reclamationDays) — the guarantee:
 *    each phase always gets this many days once it starts;
 *  · the boundary DATES (receivingEnd…reclamationEnd) — the current forecast,
 *    RE-DERIVED on every manual transition from phaseStartedAt.
 *
 * Option A semantics: closing a phase early does not shrink the next phase;
 * the whole downstream calendar shifts earlier instead.
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SessionType type = SessionType.CANDIDACY;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "total_days", nullable = false)
    private int totalDays;

    /* ── allotted durations (the guarantee) ── */
    @Column(name = "receiving_days", nullable = false)
    private int receivingDays;

    @Column(name = "review_days", nullable = false)
    private int reviewDays;

    @Column(name = "correction_days", nullable = false)
    private int correctionDays;

    @Column(name = "reclamation_days", nullable = false)
    private int reclamationDays;

    /* ── forecast boundaries (re-derived on each transition) ── */
    @Column(name = "receiving_end", nullable = false)
    private LocalDate receivingEnd;

    @Column(name = "review_end", nullable = false)
    private LocalDate reviewEnd;

    @Column(name = "correction_end", nullable = false)
    private LocalDate correctionEnd;

    @Column(name = "reclamation_end", nullable = false)
    private LocalDate reclamationEnd;

    /** When the CURRENT phase actually began. */
    @Column(name = "phase_started_at", nullable = false)
    private LocalDate phaseStartedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.PLANNED;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Days allotted to a given phase (0 for PLANNED/CLOSED). */
    public int allottedDaysFor(SessionStatus phase) {
        return switch (phase) {
            case RECEIVING -> receivingDays;
            case REVIEW -> reviewDays;
            case CORRECTION -> correctionDays;
            case RECLAMATION -> reclamationDays;
            default -> 0;
        };
    }

    /** Planned end of the CURRENT phase, or null outside the active phases. */
    public LocalDate currentPhaseEnd() {
        return switch (status) {
            case RECEIVING -> receivingEnd;
            case REVIEW -> reviewEnd;
            case CORRECTION -> correctionEnd;
            case RECLAMATION -> reclamationEnd;
            default -> null;
        };
    }
}
