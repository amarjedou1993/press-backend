-- ═══════════════════════════════════════════════════════════════════
-- V9__objections.sql
-- The candidate's right to contest a rejection (V1.3 §J).
--
-- FULLY IDEMPOTENT. The V1 schema already created `objections` and
-- `objection_reasons` in their original ERD shape, so this migration EXTENDS
-- what exists rather than assuming it must create it. Every statement can run
-- against either state.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. the grounds a candidate may invoke ──────────────────────────
-- A closed list rather than free text alone: it tells the second reviewer
-- WHERE to look before they read a word, and it lets HAPA report on what is
-- actually being contested across a session.
CREATE TABLE IF NOT EXISTS objection_reasons (
    id       BIGSERIAL PRIMARY KEY,
    label_fr VARCHAR(200) NOT NULL,
    label_ar VARCHAR(200) NOT NULL,
    active   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Columns V1 did not have. IF NOT EXISTS so this is safe either way.
ALTER TABLE objection_reasons
    ADD COLUMN IF NOT EXISTS code          VARCHAR(40),
    ADD COLUMN IF NOT EXISTS hint_fr       TEXT,
    ADD COLUMN IF NOT EXISTS hint_ar       TEXT,
    ADD COLUMN IF NOT EXISTS display_order INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS active        BOOLEAN NOT NULL DEFAULT TRUE;

-- `code` is how the application refers to a ground, so it must be unique.
-- A UNIQUE INDEX rather than a constraint: CREATE ... IF NOT EXISTS exists
-- for indexes and does not for constraints.
CREATE UNIQUE INDEX IF NOT EXISTS uq_objection_reasons_code
    ON objection_reasons (code);

COMMENT ON TABLE objection_reasons IS
    'Closed list of grounds for contesting a rejection. Data, not code: HAPA '
    'can add or retire a ground without a deployment.';

-- ── 2. the objection itself ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS objections (
    id             BIGSERIAL PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    reason_id      BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE objections
    -- What the candidate actually disputes. A second reviewer re-examining a
    -- whole dossier needs to know WHAT is contested, not only under which
    -- heading.
    ADD COLUMN IF NOT EXISTS argument TEXT,
    -- The decision being contested — pinned, so the record stays unambiguous
    -- once the reclamation produces a decision of its own.
    ADD COLUMN IF NOT EXISTS contested_decision_id BIGINT;

-- `argument` is required going forward. Any pre-existing row (there should be
-- none) gets an empty string rather than blocking the migration.
UPDATE objections SET argument = '' WHERE argument IS NULL;
ALTER TABLE objections ALTER COLUMN argument SET NOT NULL;

-- The once-only right, as a CONSTRAINT rather than a convention: a right that
-- could be exercised twice could be exercised indefinitely.
CREATE UNIQUE INDEX IF NOT EXISTS uq_objections_application
    ON objections (application_id);

CREATE INDEX IF NOT EXISTS idx_objections_created
    ON objections (created_at DESC);

-- Foreign keys, added only if absent. ALTER ... ADD CONSTRAINT has no
-- IF NOT EXISTS, so the catalogue is consulted first.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_objections_application') THEN
        ALTER TABLE objections
            ADD CONSTRAINT fk_objections_application
            FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_objections_reason') THEN
        ALTER TABLE objections
            ADD CONSTRAINT fk_objections_reason
            FOREIGN KEY (reason_id) REFERENCES objection_reasons(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_objections_contested_decision') THEN
        ALTER TABLE objections
            ADD CONSTRAINT fk_objections_contested_decision
            FOREIGN KEY (contested_decision_id) REFERENCES review_decisions(id);
    END IF;
END $$;

COMMENT ON COLUMN objections.application_id IS
    'UNIQUE — the once-only objection right, enforced by the database.';

COMMENT ON COLUMN objections.contested_decision_id IS
    'The rejection being contested. Pinned so the record stays unambiguous '
    'even after the reclamation produces its own decision.';

-- ── 3. the different-reviewer rule, at the database ────────────────
-- V1.3 §J: a reclamation must be examined by someone other than the author of
-- the rejection. The service enforces this too, but the constraint is what
-- makes it TRUE rather than merely intended — a future code path cannot
-- bypass it.
CREATE OR REPLACE FUNCTION check_reclamation_reviewer_differs()
RETURNS TRIGGER AS $$
DECLARE
    rejecter_id BIGINT;
BEGIN
    IF NEW.round <> 'RECLAMATION' THEN
        RETURN NEW;
    END IF;

    SELECT reviewer_id INTO rejecter_id
    FROM review_decisions
    WHERE application_id = NEW.application_id
      AND decision = 'REJECT'
      AND round <> 'RECLAMATION'
    ORDER BY created_at DESC
    LIMIT 1;

    IF rejecter_id IS NOT NULL AND rejecter_id = NEW.reviewer_id THEN
        RAISE EXCEPTION
            'Une réclamation ne peut pas être examinée par l''auteur de la '
            'décision contestée (dossier %, membre %)',
            NEW.application_id, NEW.reviewer_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_reclamation_reviewer_differs ON review_decisions;
CREATE TRIGGER trg_reclamation_reviewer_differs
    BEFORE INSERT ON review_decisions
    FOR EACH ROW EXECUTE FUNCTION check_reclamation_reviewer_differs();

-- ── 4. the grounds themselves ──────────────────────────────────────
-- PROVISIONAL: HAPA owes the final wording (project plan, "Pending inputs",
-- item 1). These cover what a rejected journalist actually disputes, and the
-- table is data — replacing them is an UPDATE, not a deployment.
--
-- ON CONFLICT (code) makes the seed re-runnable, and the UPDATE clause means
-- re-running also refreshes wording that has changed.
INSERT INTO objection_reasons (code, label_fr, label_ar, hint_fr, display_order, active)
VALUES
    ('DOCUMENT_NOT_CONSIDERED',
     'Pièce justificative non prise en compte',
     'وثيقة مقدمة لم تؤخذ بعين الاعتبار',
     'Vous aviez fourni une pièce qui ne semble pas avoir été examinée.',
     1, TRUE),

    ('MATERIAL_ERROR',
     'Erreur matérielle dans la décision',
     'خطأ مادي في القرار',
     'La décision contient une inexactitude de fait (identité, dates, pièces citées).',
     2, TRUE),

    ('WRONG_CATEGORY_ASSESSMENT',
     'Appréciation contestée de la catégorie',
     'الطعن في تقدير الفئة',
     'Vous estimez que votre situation professionnelle a été mal appréciée.',
     3, TRUE),

    ('INSUFFICIENT_JUSTIFICATION',
     'Motivation insuffisante de la décision',
     'عدم كفاية تعليل القرار',
     'Le motif communiqué ne vous permet pas de comprendre le refus.',
     4, TRUE),

    ('NEW_EVIDENCE',
     'Éléments nouveaux à porter à la connaissance de la commission',
     'عناصر جديدة لعرضها على اللجنة',
     'Vous disposez d''éléments qui n''ont pas pu être présentés lors de l''examen.',
     5, TRUE),

    ('OTHER',
     'Autre motif',
     'سبب آخر',
     'Exposez précisément votre contestation dans le champ ci-dessous.',
     99, TRUE)
ON CONFLICT (code) DO UPDATE SET
    label_fr      = EXCLUDED.label_fr,
    label_ar      = EXCLUDED.label_ar,
    hint_fr       = EXCLUDED.hint_fr,
    display_order = EXCLUDED.display_order,
    active        = EXCLUDED.active;
