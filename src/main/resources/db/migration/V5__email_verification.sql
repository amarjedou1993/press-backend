-- ═══════════════════════════════════════════════════════════════════
-- V5__email_verification.sql
-- E-mail verification (feedback §3.1) — policy: login is ALLOWED while
-- unverified, but SUBMISSION is blocked. The candidate can explore, build
-- their profile and assemble documents; only the act that carries legal
-- weight requires a corroborated address.
--
-- One token table serves all three flows (verification, password reset,
-- e-mail change) because they are the same machinery: a single-use secret
-- with an expiry, sent to an address to prove control of it.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at TIMESTAMPTZ;

-- Existing accounts pre-date verification: treat them as verified rather
-- than locking out people who registered before the feature existed.
UPDATE users SET email_verified = TRUE, email_verified_at = now();

-- Staff accounts are created by the Super Admin, who has already vetted the
-- address out of band; they never go through the self-service flow.
COMMENT ON COLUMN users.email_verified IS
    'Candidates verify by e-mail link. Staff accounts are created verified.';

-- ───────────────────────────────────────────────────────────────────
-- email_tokens — one table, three purposes
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE email_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SHA-256 of the token. The raw value exists only in the e-mail: a
    -- database leak therefore yields no usable links.
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    type        VARCHAR(20)  NOT NULL
                CHECK (type IN ('VERIFY_EMAIL', 'PASSWORD_RESET', 'EMAIL_CHANGE')),
    -- EMAIL_CHANGE only: the address being claimed.
    new_email   VARCHAR(255),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT email_change_carries_address
        CHECK (type <> 'EMAIL_CHANGE' OR new_email IS NOT NULL)
);

-- Lookup is always by hash; only unused tokens matter.
CREATE INDEX idx_email_tokens_lookup
    ON email_tokens (token_hash)
    WHERE used_at IS NULL;

-- Cleanup job (and "resend" invalidation) scan by user + type.
CREATE INDEX idx_email_tokens_user_type
    ON email_tokens (user_id, type)
    WHERE used_at IS NULL;
