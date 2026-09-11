-- ═══════════════════════════════════════════════════════════════════
-- RENEWAL — a second kind of session, and the link between two cards.
--
-- ⚠️ A RENEWAL IS A CANDIDATURE, NOT AN AUTOMATIC ACT.
--
-- A press card certifies that its holder is CURRENTLY a working journalist.
-- Renewing without examination means the Authority re-certifies that for
-- someone who may have left the profession, or been dismissed for
-- fabrication. So a renewal goes through the commission like any other
-- dossier — it is simply a lighter one, because identity does not change and
-- employment does.
--
-- ⚠️ AND IT REUSES THE SESSION MACHINERY DELIBERATELY.
--
-- sessions.type was an enum with a single value; the design anticipated a
-- second. Renewals therefore inherit phases, deadlines, the commission's
-- queue, the printer's session list and the cohort expiry — none of which had
-- to be built again, and none of which can drift from the candidacy path
-- because it is the same path.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. the session type ────────────────────────────────────────────
ALTER TABLE sessions
    DROP CONSTRAINT IF EXISTS sessions_type_check;
ALTER TABLE sessions
    ADD CONSTRAINT sessions_type_check
        CHECK (type IN ('CANDIDACY', 'RENEWAL'));

COMMENT ON COLUMN sessions.type IS
    'CANDIDACY: open to anyone. RENEWAL: open only to holders of a card '
    'expiring in this cycle, or lapsed within the previous one.';

-- ── 2. the chain between cards ─────────────────────────────────────
ALTER TABLE cards
    ADD COLUMN IF NOT EXISTS renewed_from_card_id BIGINT
        REFERENCES cards(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_cards_renewed_from
    ON cards (renewed_from_card_id)
    WHERE renewed_from_card_id IS NOT NULL;

COMMENT ON COLUMN cards.renewed_from_card_id IS
    'The card this one replaces. Makes "which card is current for this '
    'person" a query rather than a telephone call — and keeps the history of '
    'an accreditation readable years later.';

-- ⚠️ ON DELETE SET NULL, not CASCADE. Deleting an old card must never take
-- the card that replaced it: the new one is in someone''s pocket.

-- ⚠️ ONE RENEWAL PER CARD. Two cards claiming to replace the same
-- predecessor means two live credentials from one accreditation, and no way
-- to say which the register means.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cards_one_renewal_per_card
    ON cards (renewed_from_card_id)
    WHERE renewed_from_card_id IS NOT NULL;

-- ── 3. what a renewal must supply ──────────────────────────────────
--
-- ⚠️ THE FLAG IS ON THE REQUIREMENT, NOT A SECOND TABLE.
--
-- A renewal needs a SUBSET of a candidature's pieces, not a different list.
-- A separate table would be the same rows written twice, and the day HAPA
-- adds a requirement it would be added to one of them.
ALTER TABLE document_requirements
    ADD COLUMN IF NOT EXISTS required_for_renewal BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN document_requirements.required_for_renewal IS
    'FALSE where the piece was verified once and does not change: birth '
    'certificate, identity document, diploma. TRUE for anything that answers '
    '"is this person still a working journalist" — which is the whole '
    'question a renewal asks.';

/*
 * ⚠️ WHAT CARRIES OVER, AND WHY EACH.
 *
 * Identity does not change, and a candidate who proved theirs in 2026 should
 * not have to prove it again in 2028 — an administration that re-asks for a
 * birth certificate it already holds is an administration nobody believes is
 * keeping records.
 *
 * Employment does change, and it is the ENTIRE subject of a renewal. A
 * contract, recent published work and the current outlet are what answer the
 * question the card asserts.
 *
 * ⚠️ AND THE PHOTOGRAPH IS RE-SUPPLIED. It is not identity paperwork — it is
 * the face an agent compares at a checkpoint, and a card issued on a
 * five-year-old photograph fails at exactly that moment. Handled outside
 * this table (candidate_profiles.photo_path), but the rule is the same and
 * the renewal screen must ask for it.
 */
UPDATE document_requirements
   SET required_for_renewal = FALSE
 WHERE doc_type IN ('BIRTH_CERTIFICATE', 'IDENTITY_DOCUMENT', 'DIPLOMA');

-- Everything else keeps the default TRUE: employment contract, published
-- work, outlet declaration, and any category-specific piece HAPA adds later.

-- ── 4. eligibility, as a view the application can trust ────────────
--
-- ⚠️ WHO MAY RENEW, DEFINED ONCE.
--
-- The rule is a date comparison with a grace period, and it decides whether
-- someone keeps their accreditation. Written in three places it would
-- eventually be three rules — so it is written here, and the service reads
-- it.
CREATE OR REPLACE VIEW renewable_cards AS
SELECT c.id                AS card_id,
       c.card_number,
       c.expires_at,
       c.status,
       a.candidate_id,
       a.category_id,
       a.session_id        AS issued_in_session_id
  FROM cards c
  JOIN applications a ON a.id = c.application_id
 WHERE c.status = 'VALID'
   -- ⚠️ Already replaced? Then it is not renewable — the successor is.
   AND NOT EXISTS (
       SELECT 1 FROM cards n WHERE n.renewed_from_card_id = c.id
   );

COMMENT ON VIEW renewable_cards IS
    'Cards that may still be renewed: valid, and not already replaced. '
    'The DATE window is applied by the application against the renewal '
    'session — a view cannot know which session is being opened.';
