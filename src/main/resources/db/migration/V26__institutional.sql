-- ═══════════════════════════════════════════════════════════════════
-- INSTITUTIONAL CARDS — the C series.
--
-- ⚠️ A THIRD PROVENANCE, NOT A THIRD KIND OF DOCUMENT.
--
-- An institution employs journalists and files them; the Ministry grants
-- their cards. No commission examines them — the body vouching for them IS a
-- press authority — so there is no dossier, no session and no review.
--
-- ⚠️ ITS OWN TABLE AND ITS OWN SEQUENCE, LIKE honour_cards.
--
-- The alternative was cards.application_id made nullable with an `origin`
-- column, sharing the A series. That would have been necessary had these
-- cards been indistinguishable from a candidate's — HAPA decided they are
-- not, and the C in the number says so.
--
-- Which makes a separate table the honest shape: the uniqueness of a card
-- number then rests on a UNIQUE index rather than on two services agreeing
-- about one sequence.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. the institutions ────────────────────────────────────────────
--
-- ⚠️ A TABLE, NOT A CONSTANT.
--
-- HAPA is the first, and today the only one. But an accreditation authority
-- that later admits a second body — a national agency, a public broadcaster —
-- should add a row, not a release. The cost of the general form is this
-- table; the cost of the specific one is a rewrite.
CREATE TABLE institutions (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL UNIQUE,
    name_fr      VARCHAR(200) NOT NULL,
    name_ar      VARCHAR(200) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE institutions IS
    'Bodies that file their own staff for accreditation. Each has ONE account '
    '(users.role = INSTITUTION) which belongs to the body rather than to a '
    'person: staff turnover must not cost an institution its access.';

INSERT INTO institutions (code, name_fr, name_ar) VALUES
    ('HAPA',
     'Haute Autorité de la Presse et de l''Audiovisuel',
     'السلطة العليا للصحافة والسمعيات البصرية');

-- ── 2. the account belongs to the body ─────────────────────────────
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users
    ADD CONSTRAINT users_role_check
        CHECK (role IN ('CANDIDATE','REVIEWER','SUPER_ADMIN','PRINTER','INSTITUTION'));

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS institution_id BIGINT
        REFERENCES institutions(id) ON DELETE RESTRICT;

-- ⚠️ AN INSTITUTION ACCOUNT MUST NAME ITS INSTITUTION, AND NO OTHER MAY.
--
-- Without this an INSTITUTION account with a null institution_id would file
-- staff belonging to nobody — and the Ministry would receive records it could
-- not attribute.
ALTER TABLE users
    ADD CONSTRAINT users_institution_matches_role
        CHECK (
            (role = 'INSTITUTION' AND institution_id IS NOT NULL)
         OR (role <> 'INSTITUTION' AND institution_id IS NULL)
        );

CREATE INDEX idx_users_institution ON users (institution_id)
    WHERE institution_id IS NOT NULL;

-- ── 3. the C sequence ──────────────────────────────────────────────
--
-- ⚠️ ITS OWN, never the A or B series'. Reset each January with the others,
-- as part of the year-opening runbook.
CREATE SEQUENCE institutional_card_number_seq START 1;

-- ── 4. the cards ───────────────────────────────────────────────────
CREATE TABLE institutional_cards (
    id                    BIGSERIAL PRIMARY KEY,
    institution_id        BIGINT       NOT NULL REFERENCES institutions(id) ON DELETE RESTRICT,

    -- the holder, as the institution filed them
    full_name             VARCHAR(200) NOT NULL,
    identity_number       VARCHAR(30)  NOT NULL,
    birthdate             DATE,
    birthplace            VARCHAR(200),
    job_title             VARCHAR(200),

    category_id           BIGINT       REFERENCES press_categories(id),
    specialisation_id     BIGINT       REFERENCES specialisations(id),

    photo_path            VARCHAR(500),
    photo_uploaded_at     TIMESTAMPTZ,

    /*
     * ── the grant ──
     *
     * ⚠️ EVERYTHING BELOW IS NULL UNTIL THE MINISTRY GRANTS.
     *
     * The institution files a person; that filing is not a card. The number,
     * the token and the signature are taken at the moment of granting — which
     * is what keeps the C sequence unbroken: a filing that is never granted,
     * or is deleted, consumes no number.
     *
     * The same discipline as CardService: every precondition checked BEFORE a
     * number is taken, because a gap in the register invites the question of
     * what was removed.
     */
    card_number           VARCHAR(30)  UNIQUE,
    verification_token    VARCHAR(64)  UNIQUE,
    signature             TEXT,
    signature_key_id      VARCHAR(50),
    issued_at             DATE,
    expires_at            DATE,
    granted_by            BIGINT       REFERENCES users(id),
    granted_at            TIMESTAMPTZ,

    -- the card's life once it exists
    status                VARCHAR(20)  NOT NULL DEFAULT 'VALID',
    status_reason         TEXT,
    status_changed_at     TIMESTAMPTZ,
    status_changed_by     BIGINT       REFERENCES users(id),

    -- provenance
    filed_by              BIGINT       NOT NULL REFERENCES users(id),
    filed_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT institutional_cards_status_check
        CHECK (status IN ('VALID','SUSPENDED','REVOKED')),

    /*
     * ⚠️ GRANTED IS ALL-OR-NOTHING.
     *
     * A row with a number but no signature would verify as forged; one with a
     * signature but no expiry would never lapse. The five fields are written
     * in a single transaction, and this refuses any other combination.
     */
    CONSTRAINT institutional_cards_grant_complete
        CHECK (
            (card_number IS NULL AND verification_token IS NULL
                AND signature IS NULL AND issued_at IS NULL
                AND expires_at IS NULL AND granted_at IS NULL)
         OR (card_number IS NOT NULL AND verification_token IS NOT NULL
                AND signature IS NOT NULL AND issued_at IS NOT NULL
                AND expires_at IS NOT NULL AND granted_at IS NOT NULL)
        )
);

/*
 * ⚠️ ONE LIVE CARD PER PERSON PER INSTITUTION.
 *
 * Partial, on granted rows that have not been revoked: an employee who leaves
 * and returns may be filed again, and a renewal replaces rather than
 * duplicates. Without it a double import gives one person two C numbers, both
 * scanning green.
 */
CREATE UNIQUE INDEX uq_institutional_live_holder
    ON institutional_cards (institution_id, identity_number)
    WHERE granted_at IS NOT NULL AND status <> 'REVOKED';

CREATE INDEX idx_institutional_by_institution
    ON institutional_cards (institution_id, status);

CREATE INDEX idx_institutional_pending
    ON institutional_cards (institution_id)
    WHERE granted_at IS NULL;

COMMENT ON COLUMN institutional_cards.granted_at IS
    'NULL means FILED but not granted: the institution has supplied the '
    'person, the Ministry has not yet issued a card. There is no status '
    'enum for it — the absence of a grant IS the state, and a nullable '
    'timestamp cannot disagree with the five fields beside it.';

-- ── 5. the history, as for every other card ────────────────────────
CREATE TABLE institutional_card_status_history (
    id                    BIGSERIAL PRIMARY KEY,
    institutional_card_id BIGINT      NOT NULL
        REFERENCES institutional_cards(id) ON DELETE CASCADE,
    from_status           VARCHAR(20),
    to_status             VARCHAR(20) NOT NULL,
    reason                TEXT,
    actor_id              BIGINT      REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_institutional_history_card
    ON institutional_card_status_history (institutional_card_id, created_at DESC);
