package com.presscard.press_accreditation.email;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One queued e-mail. Maps to the email_outbox table from V1__init.sql.
 *
 * WHY AN OUTBOX rather than sending inline:
 *
 *  · TRANSACTIONAL INTEGRITY — the row is written in the SAME transaction as
 *    the action it announces. If registration rolls back, no verification
 *    mail is sent; if the mail server is unreachable, registration still
 *    succeeds. The two can never disagree.
 *  · DURABILITY — a mail server outage delays delivery, never loses it. The
 *    worker retries.
 *  · SPEED — the candidate's request returns immediately; SMTP happens later
 *    on another thread.
 *
 * For an accreditation system these mails carry deadlines and decisions;
 * losing one silently would be a real failure, not an inconvenience.
 */
@Entity
@Table(name = "email_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailOutbox {

    public enum Status { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String recipient;

    /** Template key — the worker maps it to a subject and a body. */
    @Column(nullable = false, length = 60)
    private String template;

    /** Template variables. JSONB, so the shape can vary per template. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
