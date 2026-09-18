package com.presscard.press_accreditation.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps 1:1 to the users table (V1__init.sql).
 *
 * Best practices applied:
 * - @Enumerated(STRING): ordinals silently corrupt data if the enum is ever
 *   reordered; strings match the DB CHECK constraints.
 * - created_at / updated_at are insertable=false, updatable=false: the
 *   DATABASE owns them (DEFAULT now() + trigger). Java can read but never
 *   write them — audit timestamps cannot be forged from application code.
 * - @Builder.Default so builder-created users get the correct defaults
 *   (without it, Lombok's builder would leave them null and violate NOT NULL).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_source", nullable = false, length = 20)
    @Builder.Default
    private UserSource userSource = UserSource.LOCAL;

    @Column(name = "khidmaty_id", unique = true)
    private String khidmatyId;

    @Column(name = "has_password", nullable = false)
    @Builder.Default
    private boolean hasPassword = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * Which body this account speaks for.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ NON-NULL FOR INSTITUTION ACCOUNTS, NULL FOR EVERY OTHER ROLE.
     *
     * A CHECK constraint refuses both other combinations, so this field and
     * `role` cannot disagree. The account belongs to the ORGANISATION rather
     * than to a person — staff turnover must not cost an institution its
     * access — which is why the link lives here rather than on a profile.
     *
     * ⚠️ AND IT IS THE ONLY PLACE THE INSTITUTION IS ESTABLISHED. Every
     * endpoint under /api/institution resolves it from the signed-in account,
     * never from a path or a body: taking it from the request would let one
     * body file staff for another, or read its roll.
     *
     * A Long rather than a @ManyToOne, like every other reference on this
     * table — the entity holds ids, and the service reads what it needs.
     * ───────────────────────────────────────────────────────────────────
     */
    @Column(name = "institution_id")
    private Long institutionId;

    /**
     * Candidates verify their address by e-mail link. Login is ALLOWED while
     * unverified — only SUBMISSION is gated (feedback §7.1) — so a candidate
     * can explore, complete their profile and assemble documents first.
     *
     * Staff accounts are created verified: the Super Admin vetted the address
     * out of band.
     */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private OffsetDateTime emailVerifiedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Builder.Default
    @Column(name = "preferred_locale", nullable = false, length = 2)
    private String preferredLocale = "ar";

    /**
     * When this producer was last told what is waiting.
     *
     * ⚠️ PRINTER ACCOUNTS ONLY, and null everywhere else. Not constrained in
     * SQL, unlike institution_id: a stray value on another role does nothing,
     * because only PrintDigestJob reads it and it only looks at producers.
     *
     * NULL means never told — which the job treats as due.
     */
    @Column(name = "print_digest_sent_at")
    private OffsetDateTime printDigestSentAt;
}
