-- V<N>__honour_cards.sql
--
-- Cards granted by the Ministry without a candidacy.
--
-- ───────────────────────────────────────────────────────────────────────
-- ⚠️ A SEPARATE TABLE, AND THE TRADE IS VISIBLE IN THE COLUMN LIST.
--
-- The fields below duplicate what `cards` reaches through a dossier: the
-- name, the identity number, the category, the specialisation, the outlet,
-- the photograph. They are the same facts.
--
-- What differs is everything AROUND them. An ordinary card has a dossier, a
-- session, a commission decision, a correction round, an objection right and
-- a cohort expiry. An honour card has none of those. Forcing both into one
-- table would mean making half that lifecycle nullable, and every query in
-- the system would then have to remember which kind it was holding.
--
-- So the duplication is deliberate, and it is the price of the separation.
-- ───────────────────────────────────────────────────────────────────────

CREATE SEQUENCE honour_card_number_seq START 1;

CREATE TABLE honour_cards (
    id                  BIGSERIAL PRIMARY KEY,

    -- B - 0001 / 26. Its own sequence, never the A series':
    -- a shared counter would leave both series holed, and a register with
    -- unexplained gaps invites the question of what was removed.
    card_number         VARCHAR(30)  NOT NULL UNIQUE,

    /* ── the holder, entered by the Ministry ── */

    full_name           VARCHAR(200) NOT NULL,

    /*
     * ⚠️ NOT OPTIONAL, because the signature is computed over it.
     *
     * Without an identity number the canonical form cannot be built, the card
     * cannot be signed, and a scan reports the Ministry's own card as
     * unverifiable — which reads as forged rather than unknown.
     */
    identity_number     VARCHAR(40)  NOT NULL,

    birthdate           DATE,
    birthplace          VARCHAR(200),

    /* ── what is printed, and what a verifier reads ──
       ⚠️ FOREIGN KEYS, not free text. "journaliste" and "photographe de
       presse" carry different access at an event, and an agent scanning a
       card needs the real category — an honorific would tell them nothing. */

    category_id         BIGINT REFERENCES press_categories(id),
    specialisation_id   BIGINT REFERENCES specialisations(id),
    institution         VARCHAR(200),

    photo_path          VARCHAR(500),

    /* ── validity ── */

    issued_at           DATE NOT NULL,

    -- ⚠️ Set by the Ministry, card by card. There is no session to inherit
    -- from, so nothing can derive it and nothing should guess.
    expires_at          DATE NOT NULL,

    /* ── lifecycle, the same three states as an ordinary card ── */

    status              VARCHAR(20) NOT NULL DEFAULT 'VALID',
    status_reason       TEXT,
    status_changed_at   TIMESTAMPTZ,
    status_changed_by   BIGINT REFERENCES users(id),

    /* ── verification ── */

    -- Opaque and random, never derived from the number: a QR reading
    -- /verifier/B-0042-26 would let anyone iterate the range and harvest
    -- every honour holder's identity and photograph.
    verification_token  VARCHAR(64) NOT NULL UNIQUE,

    signature           TEXT,
    signature_key_id    VARCHAR(40),

    /* ── the grant ── */

    granted_by          BIGINT NOT NULL REFERENCES users(id),

    /*
     * ⚠️ MANDATORY, for the reason a justification is mandatory on a
     * rejection: this card bypasses the examination every other card
     * requires, and the record must say on whose authority and why.
     */
    grant_reason        TEXT NOT NULL,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT honour_cards_status_valid
        CHECK (status IN ('VALID', 'SUSPENDED', 'REVOKED'))
);

CREATE INDEX honour_cards_by_status ON honour_cards (status);

COMMENT ON TABLE honour_cards IS
    'Cards granted without a candidacy. Numbered in the B series, verifiable '
    'by QR, and deliberately absent from the public register.';
