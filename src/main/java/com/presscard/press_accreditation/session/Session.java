package com.presscard.press_accreditation.session;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A candidacy session (V1.3 §F). Maps 1:1 to the sessions table.
 *
 * The four phase-boundary dates are DERIVED at creation from start_date +
 * the per-phase day counts, then stored. The DB CHECK constraints guarantee
 * they stay ordered and consistent with total_days — the entity trusts them.
 *
 * status drives the manual phase machine; the boundary dates are the planned
 * calendar (targets + future email triggers), NOT automatic switches.
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

    @Column(name = "receiving_end", nullable = false)
    private LocalDate receivingEnd;

    @Column(name = "review_end", nullable = false)
    private LocalDate reviewEnd;

    @Column(name = "correction_end", nullable = false)
    private LocalDate correctionEnd;

    @Column(name = "reclamation_end", nullable = false)
    private LocalDate reclamationEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.PLANNED;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
