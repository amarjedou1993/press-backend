-- ═══════════════════════════════════════════════════════════════════
-- V12__specialisation_and_institution.sql
--
-- The card prints four things about its holder: name, category,
-- SPECIALISATION (التخصص) and INSTITUTION (المؤسسة). We collected the first
-- two. Without the other two no card can be printed at all.
--
-- THEY LIVE ON THE APPLICATION, not the profile — the same reasoning as the
-- photo snapshot. A journalist changes employer; last year's card must keep
-- saying who they worked for when it was issued. Identity belongs to the
-- person, employment belongs to the moment.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. specialisations: a closed list, owned by HAPA ───────────────
-- Every sample card reads صحفي, which is a controlled vocabulary rather than
-- free text. Data, not code: HAPA adds or retires one with an UPDATE.
CREATE TABLE IF NOT EXISTS specialisations (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(40)  NOT NULL UNIQUE,
    label_fr      VARCHAR(120) NOT NULL,
    label_ar      VARCHAR(120) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

COMMENT ON TABLE specialisations IS
    'What the holder does — printed as التخصص. A closed list so the card''s '
    'wording stays consistent across every issuance.';

-- ── 2. the application carries both ────────────────────────────────
ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS specialisation_id BIGINT
        REFERENCES specialisations(id),

    -- Free text, deliberately. The samples include "أسوشيتد برس (AP)" —
    -- international agencies appear, and no maintained list would stay
    -- complete. A closed list here would block a legitimate candidate.
    ADD COLUMN IF NOT EXISTS institution VARCHAR(200);

COMMENT ON COLUMN applications.institution IS
    'The outlet the holder works for — printed as المؤسسة. Free text: no '
    'list of media organisations stays complete, and one that lags blocks a '
    'legitimate candidate.';

-- ── 3. the card keeps its own copy ─────────────────────────────────
-- SNAPSHOT, like the photograph. A card is a dated document: if the holder
-- moves to another outlet in 2027, the 2026 card must still say what it said
-- when it was issued.
ALTER TABLE cards
    ADD COLUMN IF NOT EXISTS specialisation_fr VARCHAR(120),
    ADD COLUMN IF NOT EXISTS specialisation_ar VARCHAR(120),
    ADD COLUMN IF NOT EXISTS institution VARCHAR(200);

COMMENT ON COLUMN cards.institution IS
    'The institution AS PRINTED on this card. A snapshot — the holder may have '
    'moved since, and the card does not change.';

-- ── 4. the seed ────────────────────────────────────────────────────
-- PROVISIONAL: HAPA owes the definitive list. Drawn from the sample cards and
-- the roles a press authority normally accredits.
INSERT INTO specialisations (code, label_fr, label_ar, display_order)
VALUES
    ('JOURNALIST',     'Journaliste',              'صحفي',              1),
    ('EDITOR',         'Rédacteur en chef',        'رئيس تحرير',        2),
    ('PHOTOJOURNALIST','Photographe de presse',    'مصور صحفي',         3),
    ('CAMERAMAN',      'Cadreur',                  'مصور تلفزيوني',     4),
    ('PRESENTER',      'Présentateur',             'مقدم برامج',        5),
    ('PRODUCER',       'Réalisateur',              'مخرج',              6),
    ('TECHNICIAN',     'Technicien',               'تقني',              7),
    ('CORRESPONDENT',  'Correspondant',            'مراسل',             8),
    ('COLUMNIST',      'Chroniqueur',              'كاتب عمود',         9)
ON CONFLICT (code) DO UPDATE SET
    label_fr      = EXCLUDED.label_fr,
    label_ar      = EXCLUDED.label_ar,
    display_order = EXCLUDED.display_order;

-- ── 5. the completeness gate ───────────────────────────────────────
-- NOT enforced as NOT NULL: dossiers submitted before this migration have
-- neither field, and a constraint would make them unsavable. The submission
-- gate refuses new submissions without them, which is where the rule belongs
-- — it can explain itself, a CHECK cannot.
CREATE INDEX IF NOT EXISTS idx_applications_specialisation
    ON applications (specialisation_id);
