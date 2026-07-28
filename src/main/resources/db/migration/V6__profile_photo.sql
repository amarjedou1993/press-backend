-- ═══════════════════════════════════════════════════════════════════
-- V6__profile_photo.sql
-- The photograph is the card's primary identifying feature, so it is
-- treated as identity data rather than as a per-application document.
--
-- TWO columns, deliberately:
--
--   candidate_profiles.photo_path — the CURRENT photo. Lives on the person,
--   so a journalist reapplying next year does not re-upload, and the
--   commission sees a face on every dossier they examine.
--
--   cards.photo_path — the photo AS ISSUED. Copied at generation time and
--   never touched again. A card is a dated document: updating a profile
--   photo in 2028 must not retroactively change what a 2026 card shows.
--   Without this snapshot, reprinting an old card would silently produce a
--   different credential from the one originally delivered.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE candidate_profiles
    ADD COLUMN photo_path VARCHAR(500),
    ADD COLUMN photo_uploaded_at TIMESTAMPTZ;

COMMENT ON COLUMN candidate_profiles.photo_path IS
    'Current identity photograph (ICAO-style). Snapshotted onto cards at issuance.';

COMMENT ON COLUMN candidate_profiles.photo_uploaded_at IS
    'When it was uploaded — used to warn the candidate that a photo has aged.';

ALTER TABLE cards
    ADD COLUMN photo_path VARCHAR(500);

COMMENT ON COLUMN cards.photo_path IS
    'The photograph as it appeared on THIS card. Immutable once issued.';

-- ───────────────────────────────────────────────────────────────────
-- A photo may be flagged for correction like any other piece of
-- evidence. It is not a row in application_documents (it belongs to the
-- person, not the application), so the flag lives on the application.
-- ───────────────────────────────────────────────────────────────────
ALTER TABLE applications
    ADD COLUMN photo_needs_correction BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN photo_observation TEXT;

COMMENT ON COLUMN applications.photo_needs_correction IS
    'A reviewer judged the photograph unusable for a credential. The one '
    'element that cannot be fixed after printing, so it gets its own flag.';
