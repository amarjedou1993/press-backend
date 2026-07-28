package com.presscard.press_accreditation.email;

import com.presscard.press_accreditation.error.InvalidTokenException;
import com.presscard.press_accreditation.error.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and consumes the single-use secrets behind e-mail verification,
 * password reset, and address change.
 *
 * Four properties, each defending something specific:
 *
 *  · 32 RANDOM BYTES from SecureRandom — not guessable, not enumerable.
 *  · ONLY THE HASH IS STORED. The raw token lives in the e-mail and nowhere
 *    else, so a database leak yields no usable links. This matters most for
 *    PASSWORD_RESET, where stored raw tokens would be spare keys to accounts.
 *  · SINGLE USE — consumption stamps used_at in the same transaction as the
 *    action it authorises, so a replayed link does nothing.
 *  · ISSUING INVALIDATES THE PREVIOUS ONE of the same type, so "resend" never
 *    leaves two live links, and a reset the user did not request is
 *    neutralised the moment they request one themselves.
 */
@Service
public class EmailTokenService {

    private static final Logger log = LoggerFactory.getLogger("EMAIL_TOKEN_AUDIT");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    /** Per user, per type, per hour — blunts mailbox flooding. */
    private static final int MAX_PER_HOUR = 5;

    private final EmailTokenRepository repository;

    public EmailTokenService(EmailTokenRepository repository) {
        this.repository = repository;
    }

    /** A freshly issued token: the RAW value (for the e-mail) and its record. */
    public record IssuedToken(String rawToken, EmailToken record) {}

    /* ══ issuing ══════════════════════════════════════════════ */

    @Transactional
    public IssuedToken issue(Long userId, EmailTokenType type) {
        return issue(userId, type, null);
    }

    /**
     * @param newEmail required for EMAIL_CHANGE — the address being claimed
     */
    @Transactional
    public IssuedToken issue(Long userId, EmailTokenType type, String newEmail) {
        OffsetDateTime now = OffsetDateTime.now();

        long recent = repository.countRecent(userId, type, now.minusHours(1));
        if (recent >= MAX_PER_HOUR) {
            throw new TooManyRequestsException(
                    "Trop de demandes. Réessayez dans une heure.");
        }

        // Exactly one live link of a given type per user.
        repository.invalidateOutstanding(userId, type, now);

        String raw = randomToken();
        EmailToken token = EmailToken.builder()
                .userId(userId)
                .tokenHash(hash(raw))
                .type(type)
                .newEmail(newEmail)
                .expiresAt(now.plus(type.ttl()))
                .build();
        repository.save(token);

        log.info("TOKEN_ISSUED user={} type={} expires={}", userId, type, token.getExpiresAt());
        return new IssuedToken(raw, token);
    }

    /* ══ consuming ════════════════════════════════════════════ */

    /**
     * Validate and CONSUME a token. Marking it used happens inside the
     * caller's transaction, so the token and the action it authorises commit
     * or roll back together — a link can never be spent without its effect
     * taking place.
     *
     * The same message for missing, expired and already-used: a caller
     * probing links learns nothing from the difference.
     */
    @Transactional
    public EmailToken consume(String rawToken, EmailTokenType expectedType) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Lien invalide ou expiré.");
        }

        EmailToken token = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Lien invalide ou expiré."));

        if (token.getType() != expectedType) {
            // A verification link must not double as a password reset.
            log.warn("TOKEN_TYPE_MISMATCH expected={} actual={}", expectedType, token.getType());
            throw new InvalidTokenException("Lien invalide ou expiré.");
        }
        if (!token.isUsable()) {
            throw new InvalidTokenException("Lien invalide ou expiré.");
        }

        token.setUsedAt(OffsetDateTime.now());
        repository.save(token);

        log.info("TOKEN_CONSUMED user={} type={}", token.getUserId(), token.getType());
        return token;
    }

    /* ══ housekeeping ═════════════════════════════════════════ */

    /** Expired tokens serve no purpose; drop them nightly. */
    @Transactional
    public int purgeExpired() {
        int removed = repository.deleteExpiredBefore(OffsetDateTime.now().minusDays(7));
        if (removed > 0) {
            log.info("TOKEN_PURGE removed={}", removed);
        }
        return removed;
    }

    /* ══ internals ════════════════════════════════════════════ */

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe, unpadded: it travels in a link.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
