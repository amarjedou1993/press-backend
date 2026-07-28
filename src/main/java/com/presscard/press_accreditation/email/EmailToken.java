package com.presscard.press_accreditation.email;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A single-use secret sent to an e-mail address.
 *
 * The raw token NEVER touches the database — only its SHA-256. A database
 * leak therefore yields no usable links, which matters most for
 * PASSWORD_RESET, where a stored raw token would be a spare key to every
 * account.
 *
 * newEmail is populated for EMAIL_CHANGE only: the address being claimed,
 * held here until the owner proves control of it.
 */
@Entity
@Table(name = "email_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex of the token. Unique: a collision would be a shared key. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailTokenType type;

    /** EMAIL_CHANGE only — the address awaiting proof of control. */
    @Column(name = "new_email", length = 255)
    private String newEmail;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Set the moment the token is consumed; a used token is never valid again. */
    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean isUsable() {
        return usedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }
}
