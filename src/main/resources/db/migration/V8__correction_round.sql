-- ═══════════════════════════════════════════════════════════════════
-- V8__correction_round.sql
-- What the correction round needs beyond V7.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. superseded documents ────────────────────────────────────────
-- A corrected document is a NEW ROW, never an overwrite: the original is
-- evidence of what was originally submitted, and a regulator that destroys
-- it cannot later show what it decided on.
--
-- But both rows then exist, and the completeness engine would count the
-- replacement TWICE. superseded_at marks the older one: NULL means current.
ALTER TABLE application_documents
    ADD COLUMN superseded_at TIMESTAMPTZ,
    ADD COLUMN superseded_by BIGINT REFERENCES application_documents(id);

COMMENT ON COLUMN application_documents.superseded_at IS
    'When this version was replaced. NULL = current. The row is never '
    'deleted: the original submission is part of the audit trail.';

-- Every "current documents" query filters on this, so it earns an index.
CREATE INDEX idx_documents_current
    ON application_documents (application_id, doc_type)
    WHERE superseded_at IS NULL;

-- A superseded row must name its replacement, and vice versa.
ALTER TABLE application_documents
    ADD CONSTRAINT document_supersession_is_coherent
        CHECK ((superseded_at IS NULL AND superseded_by IS NULL)
            OR (superseded_at IS NOT NULL AND superseded_by IS NOT NULL));

-- ── 2. the deadline warning ────────────────────────────────────────
-- A candidate whose correction window closes without warning has been
-- trapped, not deadlined. One warning, 48 h out — and recorded, so a
-- restarted job never sends it twice.
ALTER TABLE applications
    ADD COLUMN correction_warning_sent_at TIMESTAMPTZ;

COMMENT ON COLUMN applications.correction_warning_sent_at IS
    'When the 48-hour correction reminder was sent. Prevents a repeat if the '
    'job runs again.';

-- ── 3. when the correction was requested ───────────────────────────
-- The deadline is the session's, but knowing when the clock started makes
-- the candidate''s remaining time explainable rather than merely displayed.
ALTER TABLE applications
    ADD COLUMN correction_requested_at TIMESTAMPTZ;

-- The nightly sweep: files awaiting a correction, by session.
CREATE INDEX idx_applications_awaiting_correction
    ON applications (session_id, status)
    WHERE status = 'CORRECTION_REQUESTED';
