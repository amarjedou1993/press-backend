-- ═══════════════════════════════════════════════════════════════════
-- V11__card_dates_are_dates.sql
--
-- issued_at and expires_at were created as TIMESTAMPTZ. A card's validity is
-- a DATE, and the difference is not cosmetic:
--
--   A timestamp expiry means a card issued at 14:00 on 1 August 2026 expires
--   at 14:00 on its last day — invalid for that afternoon, while the holder
--   is still carrying a card that reads "valable jusqu'au 31 juillet 2027".
--   Nobody tells a journalist their accreditation lapses at 14:32:07.
--
--   A DATE expiry means the whole of the printed day is valid, which is what
--   the printed card says and what the holder will expect.
--
-- The cast is safe: no cards have been issued yet, and any that existed would
-- keep their calendar day.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE cards
    ALTER COLUMN issued_at  TYPE DATE USING issued_at::date,
    ALTER COLUMN expires_at TYPE DATE USING expires_at::date;

COMMENT ON COLUMN cards.issued_at IS
    'The accreditation date printed on the card. A DATE, not an instant.';

COMMENT ON COLUMN cards.expires_at IS
    'The last day the card is valid, inclusive. Expiry is derived by comparing '
    'this to today — never stored as a status, so no failed job can leave a '
    'lapsed card reading "valide".';
