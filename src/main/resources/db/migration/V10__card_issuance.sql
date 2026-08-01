-- ═══════════════════════════════════════════════════════════════════
-- V10__card_issuance.sql
-- The credential itself: numbering, lifecycle, and verification.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. the card's own columns ──────────────────────────────────────
ALTER TABLE cards
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'VALID',

    -- The QR's payload. RANDOM AND OPAQUE, never derived from the card
    -- number: a QR reading /verifier/HAPA-2026-00042 would let anyone iterate
    -- the range and harvest the identity and photograph of every accredited
    -- journalist in Mauritania. Only someone HOLDING the card can look it up.
    ADD COLUMN IF NOT EXISTS verification_token VARCHAR(32),

    -- A detached Ed25519 signature over the card's canonical fields.
    -- Verification is online, so this is not what secures the lookup — it is
    -- EVIDENCE. If a card's authenticity is ever disputed, HAPA can prove it
    -- issued this exact card against a published public key, without asking
    -- anyone to trust its database.
    ADD COLUMN IF NOT EXISTS signature TEXT,
    ADD COLUMN IF NOT EXISTS signature_key_id VARCHAR(40),

    -- Who issued it, and when the artefact was last produced. A reprint does
    -- not create a new accreditation — same number, new PDF, recorded.
    ADD COLUMN IF NOT EXISTS issued_by BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS printed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS print_count INT NOT NULL DEFAULT 0,

    -- Lifecycle, when someone acts on it.
    ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS status_changed_by BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS status_reason TEXT;

-- Only someone holding the card may resolve it, so the token is the lookup key.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cards_verification_token
    ON cards (verification_token);

-- NOTE: EXPIRED is deliberately absent. Expiry is a DATE FACT derived from
-- expires_at, not a decision anyone takes — storing it would mean a nightly
-- job could fail and a lapsed card would still read "valide" to whoever
-- scanned it.
ALTER TABLE cards DROP CONSTRAINT IF EXISTS card_status_valid;
ALTER TABLE cards
    ADD CONSTRAINT card_status_valid
        CHECK (status IN ('VALID', 'SUSPENDED', 'REVOKED'));

-- A status change that is not VALID must say who and why: withdrawing a
-- journalist's accreditation mid-year is a serious act and must be as
-- auditable as the decision that granted it.
ALTER TABLE cards DROP CONSTRAINT IF EXISTS card_status_change_is_accounted_for;
ALTER TABLE cards
    ADD CONSTRAINT card_status_change_is_accounted_for
        CHECK (status = 'VALID'
            OR (status_changed_by IS NOT NULL
                AND status_changed_at IS NOT NULL
                AND status_reason IS NOT NULL));

COMMENT ON COLUMN cards.verification_token IS
    'Opaque random token in the QR. Never derived from the card number — a '
    'guessable URL would expose every accredited journalist''s identity.';

COMMENT ON COLUMN cards.signature IS
    'Ed25519 signature over the canonical card string. Evidence of issuance, '
    'verifiable against a published public key.';

COMMENT ON COLUMN cards.signature_key_id IS
    'Which signing key produced this signature. Cards outlive key rotations.';

-- ── 2. the lifecycle, as an audit trail ────────────────────────────
-- The card row carries only its CURRENT status; every change is a row here.
-- The same shape as status_history for applications, and for the same reason:
-- for a regulator, the audit trail is the product.
CREATE TABLE IF NOT EXISTS card_status_history (
    id          BIGSERIAL PRIMARY KEY,
    card_id     BIGINT      NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    reason      TEXT        NOT NULL,
    actor_id    BIGINT      NOT NULL REFERENCES users(id),
    -- For a revocation: the commission member who proposed it. The act
    -- follows the chain that granted the accreditation.
    proposed_by BIGINT      REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_card_status_history_card
    ON card_status_history (card_id, created_at DESC);

-- ── 3. numbering ───────────────────────────────────────────────────
-- Year-scoped and sequential: HAPA-2026-00001.
--
-- A SEQUENCE rather than MAX(number)+1, because two administrators generating
-- batches at the same moment would otherwise both read the same maximum and
-- produce duplicate numbers — on a printed credential that is unrecoverable.
CREATE SEQUENCE IF NOT EXISTS card_number_seq START WITH 1;

COMMENT ON SEQUENCE card_number_seq IS
    'Card numbering. Reset to 1 at the start of each accreditation year, as '
    'part of the year-opening runbook.';
