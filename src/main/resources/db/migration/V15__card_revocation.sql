-- ═══════════════════════════════════════════════════════════════════
-- V14__card_revocation.sql
-- Withdrawing a card, and the two hands that must agree to it.
--
-- SUSPENSION and REVOCATION are different acts and carry different bars.
--
--   SUSPENSION is precautionary and REVERSIBLE — a card reported stolen, a
--   holder under investigation. The super admin acts alone and immediately:
--   waiting for a committee while a stolen card circulates helps nobody.
--
--   REVOCATION is punitive and TERMINAL. It strips a journalist's
--   accreditation mid-year, so it follows the chain that GRANTED it —
--   proposed by a commission member, executed by the Authority.
--
-- The reason is not procedural neatness. If a revocation is challenged, HAPA
-- must be able to show it followed the same path as the grant. A super admin
-- acting alone can be characterised as an administrative act against a
-- journalist; a commission proposal executed by the Authority cannot.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. the grounds ────────────────────────────────────────────────
-- A closed list, like objection reasons: it tells the super admin what is
-- being alleged before they read a word, and it lets HAPA report on why cards
-- are being withdrawn across a cycle.
CREATE TABLE IF NOT EXISTS revocation_grounds (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(40)  NOT NULL UNIQUE,
    label_fr      VARCHAR(200) NOT NULL,
    label_ar      VARCHAR(200) NOT NULL,
    hint_fr       TEXT,
    /** TRUE where the ground implies immediate suspension pending the decision. */
    warrants_immediate_suspension BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

COMMENT ON TABLE revocation_grounds IS
    'Closed list of grounds for withdrawing a card. Data, not code: HAPA can '
    'add or retire a ground without a deployment.';

-- ── 2. the proposal ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS revocation_proposals (
    id            BIGSERIAL PRIMARY KEY,

    card_id       BIGINT      NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    ground_id     BIGINT      NOT NULL REFERENCES revocation_grounds(id),

    -- What the proposer alleges, in their own words. The super admin is being
    -- asked to end someone's accreditation; a ground alone does not give them
    -- enough to decide, and would not survive being challenged.
    statement     TEXT        NOT NULL,

    proposed_by   BIGINT      NOT NULL REFERENCES users(id),
    proposed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- PENDING | EXECUTED | DECLINED | WITHDRAWN
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- The Authority's answer. decided_note is required on DECLINED: a refusal
    -- the proposer cannot read is a refusal they will simply repeat.
    decided_by    BIGINT      REFERENCES users(id),
    decided_at    TIMESTAMPTZ,
    decided_note  TEXT
);

ALTER TABLE revocation_proposals
    DROP CONSTRAINT IF EXISTS revocation_proposal_status_valid;
ALTER TABLE revocation_proposals
    ADD CONSTRAINT revocation_proposal_status_valid
        CHECK (status IN ('PENDING', 'EXECUTED', 'DECLINED', 'WITHDRAWN'));

-- A decided proposal must name who decided it and when. The audit trail of a
-- withdrawal is the whole defence of the withdrawal.
ALTER TABLE revocation_proposals
    DROP CONSTRAINT IF EXISTS revocation_decision_is_accounted_for;
ALTER TABLE revocation_proposals
    ADD CONSTRAINT revocation_decision_is_accounted_for
        CHECK (status = 'PENDING'
            OR status = 'WITHDRAWN'
            OR (decided_by IS NOT NULL AND decided_at IS NOT NULL));

-- A refusal must be explained.
ALTER TABLE revocation_proposals
    DROP CONSTRAINT IF EXISTS revocation_refusal_is_explained;
ALTER TABLE revocation_proposals
    ADD CONSTRAINT revocation_refusal_is_explained
        CHECK (status <> 'DECLINED' OR decided_note IS NOT NULL);

-- ONE PENDING PROPOSAL PER CARD. Without this, two members could propose
-- against the same card on different grounds and the super admin would be
-- executing one while the other stayed open — leaving a live proposal against
-- an already-revoked card.
CREATE UNIQUE INDEX IF NOT EXISTS uq_revocation_one_pending_per_card
    ON revocation_proposals (card_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_revocation_pending
    ON revocation_proposals (status, proposed_at DESC);

COMMENT ON COLUMN revocation_proposals.statement IS
    'What the proposer alleges. The Authority is being asked to end an '
    'accreditation — a ground alone would not survive a challenge.';

-- ── 3. the grounds themselves ─────────────────────────────────────
-- PROVISIONAL: HAPA owes the definitive wording. These cover what a press
-- regulator actually withdraws a card for, and the table is data — replacing
-- them is an UPDATE, not a deployment.
INSERT INTO revocation_grounds
    (code, label_fr, label_ar, hint_fr, warrants_immediate_suspension, display_order)
VALUES
    ('FRAUDULENT_APPLICATION',
     'Dossier frauduleux',
     'ملف احتيالي',
     'Des pièces produites à l''appui de la candidature se révèlent fausses.',
     TRUE, 1),

    ('CEASED_ACTIVITY',
     'Cessation de l''activité journalistique',
     'التوقف عن النشاط الصحفي',
     'Le titulaire n''exerce plus l''activité au titre de laquelle la carte a été délivrée.',
     FALSE, 2),

    ('MISUSE_OF_CARD',
     'Usage abusif de la carte',
     'استعمال تعسفي للبطاقة',
     'La carte a été utilisée à des fins étrangères à l''exercice du journalisme.',
     TRUE, 3),

    ('ETHICS_BREACH',
     'Manquement grave à la déontologie',
     'إخلال جسيم بأخلاقيات المهنة',
     'Constaté par la commission dans l''exercice de ses attributions.',
     FALSE, 4),

    ('HOLDER_REQUEST',
     'À la demande du titulaire',
     'بطلب من صاحب البطاقة',
     'Le titulaire demande lui-même le retrait de sa carte.',
     FALSE, 5),

    ('DECEASED',
     'Décès du titulaire',
     'وفاة صاحب البطاقة',
     'Retrait administratif, sans caractère disciplinaire.',
     FALSE, 6),

    ('OTHER',
     'Autre motif',
     'سبب آخر',
     'Exposez précisément les faits dans le champ ci-dessous.',
     FALSE, 99)
ON CONFLICT (code) DO UPDATE SET
    label_fr      = EXCLUDED.label_fr,
    label_ar      = EXCLUDED.label_ar,
    hint_fr       = EXCLUDED.hint_fr,
    warrants_immediate_suspension = EXCLUDED.warrants_immediate_suspension,
    display_order = EXCLUDED.display_order,
    active        = TRUE;
