-- ═══════════════════════════════════════════════════════════════════
-- V13__session_card_expiry.sql
--
-- THE EXPIRY BELONGS TO THE SESSION, not to the issuance date.
--
-- A press card is an accreditation for a CYCLE. Everyone accredited in the
-- 2026 session holds a card for the same period, and renewal comes round for
-- all of them together. Computed per card, a journalist issued in March would
-- renew in March and one issued in August in August — turning a once-a-cycle
-- administrative act into a continuous one.
--
-- It also makes a SHARED CARD BACK safe: the expiry is the only per-card
-- element left on the back, so a single common expiry means one back can be
-- printed for a whole batch.
--
-- Confirmed with HAPA, and consistent with the adopted cards: every sample in
-- series A / 26 carries the same 2028/05/01.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS card_expiry_date DATE;

COMMENT ON COLUMN sessions.card_expiry_date IS
    'The expiry printed on every card issued from this session. Set when the '
    'session is created; a card''s validity is a property of the accreditation '
    'cycle, not of the day it happened to be printed.';

-- NOT NULL is deliberately NOT applied: sessions created before this migration
-- have no expiry, and CardService falls back to validityDays for them. A
-- constraint here would make those sessions unusable rather than merely
-- legacy.

-- The expiry must come after the session ends — a card that lapses before the
-- session that granted it would be absurd, and it is the kind of typo a date
-- picker makes easy.
ALTER TABLE sessions DROP CONSTRAINT IF EXISTS session_card_expiry_is_future;
ALTER TABLE sessions
    ADD CONSTRAINT session_card_expiry_is_future
        CHECK (card_expiry_date IS NULL
            OR reclamation_end IS NULL
            OR card_expiry_date > reclamation_end);
