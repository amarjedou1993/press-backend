package com.presscard.press_accreditation.email;

import java.time.Duration;

/**
 * The three flows served by one token table. They are the same machinery —
 * a single-use secret with an expiry, sent to an address to prove control of
 * it — so they share a table, a hashing scheme, and a consumption path.
 *
 * The TTLs differ by risk. A verification link is a convenience and can live
 * a day; a password-reset link is a key to the account and should not.
 */
public enum EmailTokenType {

    /** Prove the address at registration. Submission is gated on this. */
    VERIFY_EMAIL(Duration.ofHours(24)),

    /** Reset a forgotten password — the shortest life, the highest risk. */
    PASSWORD_RESET(Duration.ofMinutes(30)),

    /** Prove control of a NEW address before switching to it. */
    EMAIL_CHANGE(Duration.ofHours(2));

    private final Duration ttl;

    EmailTokenType(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }
}
