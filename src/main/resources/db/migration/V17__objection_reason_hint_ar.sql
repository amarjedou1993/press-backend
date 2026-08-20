-- The Arabic twin of the hint shown beneath each ground.
--
-- ⚠️ NULLABLE, and deliberately. The Ministry supplies these texts, and they
-- may arrive in French first. A NOT NULL column would force placeholder
-- Arabic into a legal notice — and a placeholder on the screen where someone
-- contests a refusal is worse than an honest absence: the frontend falls back
-- to hintFr, which at least says something true.

ALTER TABLE objection_reasons
    ADD COLUMN IF NOT EXISTS hint_ar TEXT;

COMMENT ON COLUMN objection_reasons.hint_ar IS
    'Arabic hint shown beneath the ground. Nullable: the screen falls back to '
    'hint_fr until the Ministry supplies the Arabic wording.';
