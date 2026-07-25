package com.presscard.press_accreditation.application;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One immutable row per state transition — the audit trail behind every
 * accreditation decision, and the data the candidate's timeline is drawn from.
 *
 * actorId is null for system actions (the correction-deadline job in week 5).
 */
@Entity
@Table(name = "status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ApplicationStatus fromStatus;   // null on creation

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private ApplicationStatus toStatus;

    @Column(name = "actor_id")
    private Long actorId;                   // null = system

    @Column(columnDefinition = "text")
    private String justification;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
