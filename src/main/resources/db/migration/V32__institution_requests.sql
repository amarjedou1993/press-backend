-- ═══════════════════════════════════════════════════════════════════
-- INSTITUTIONS APPLY; THE MINISTRY DECIDES.
--
-- ⚠️ A REQUEST IS NOT AN ACCOUNT, and it lives in its own table.
--
-- A pending institution cannot sit in `users`: users_institution_matches_role
-- requires an INSTITUTION account to name its institution, and the
-- institution does not exist until the Ministry approves. More importantly,
-- nothing an outsider typed should become an account or a register entry
-- until someone has read the formal letter.
--
-- On approval, the institution and its account are created in one
-- transaction, from this row. On rejection, the row stays, with its reason:
-- who asked, and why they were refused, is part of the record.
-- ═══════════════════════════════════════════════════════════════════

CREATE TABLE institution_requests (
    id                        BIGSERIAL PRIMARY KEY,

    -- what the body declares about itself
    proposed_name_fr          VARCHAR(200) NOT NULL,
    proposed_name_ar          VARCHAR(200) NOT NULL,
    contact_name              VARCHAR(200) NOT NULL,
    contact_role              VARCHAR(200) NOT NULL,
    email                     VARCHAR(255) NOT NULL,
    phone                     VARCHAR(40),
    locale                    VARCHAR(5)   NOT NULL DEFAULT 'fr',

    -- ⚠️ HASHED AT SUBMISSION, copied to the account on approval, and
    -- cleared on rejection or expiry. Never stored in clear, never kept
    -- longer than the decision needs it.
    password_hash             VARCHAR(255),

    -- the formal letter: the only evidence the request carries
    letter_path               VARCHAR(500) NOT NULL,

    status                    VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',

    -- proof that someone controls the address, before the Ministry reads it
    verification_token_hash   VARCHAR(64)  UNIQUE,
    verification_expires_at   TIMESTAMPTZ,
    verified_at               TIMESTAMPTZ,

    -- the decision
    decided_by                BIGINT       REFERENCES users(id),
    decided_at                TIMESTAMPTZ,
    decision_reason           TEXT,
    institution_id            BIGINT       REFERENCES institutions(id),

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT institution_requests_status_check
        CHECK (status IN ('SUBMITTED','PENDING_REVIEW','APPROVED','REJECTED','EXPIRED')),

    -- ⚠️ A REFUSAL CARRIES ITS REASON, as everywhere else in this system.
    CONSTRAINT institution_requests_rejection_reasoned
        CHECK (status <> 'REJECTED'
               OR (decision_reason IS NOT NULL AND decided_by IS NOT NULL)),

    -- ⚠️ AN APPROVAL NAMES WHAT IT CREATED and who created it.
    CONSTRAINT institution_requests_approval_complete
        CHECK (status <> 'APPROVED'
               OR (institution_id IS NOT NULL AND decided_by IS NOT NULL))
);

-- ⚠️ ONE OPEN REQUEST PER ADDRESS. A second submission while the first is
-- still waiting would be two files for one decision. After a rejection or an
-- expiry, the address may apply again — and the history keeps both.
CREATE UNIQUE INDEX uq_institution_requests_open_email
    ON institution_requests (lower(email))
    WHERE status IN ('SUBMITTED','PENDING_REVIEW');

CREATE INDEX idx_institution_requests_status
    ON institution_requests (status, created_at DESC);
