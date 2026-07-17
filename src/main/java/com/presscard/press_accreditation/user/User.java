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

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
