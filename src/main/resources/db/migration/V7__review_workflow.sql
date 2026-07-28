-- ═══════════════════════════════════════════════════════════════════
-- V7__review_workflow.sql
-- What the commission needs beyond what V1 provided.
-- ═══════════════════════════════════════════════════════════════════

-- ── 1. when a claim was made ───────────────────────────────────────
-- claimed_by told us WHO holds a dossier but not SINCE WHEN, so a claim
-- made before a reviewer's absence could freeze a candidate's file
-- indefinitely with nothing to detect it.
ALTER TABLE applications
    ADD COLUMN claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN applications.claimed_at IS
    'When the current reviewer claimed this dossier. Drives the stale-claim '
    'release: a reviewer''s absence must not block a candidate.';

-- A claim is a pair: both set, or neither.
ALTER TABLE applications
    ADD CONSTRAINT application_claim_is_coherent
        CHECK ((claimed_by IS NULL AND claimed_at IS NULL)
            OR (claimed_by IS NOT NULL AND claimed_at IS NOT NULL));

-- The pool query: unclaimed dossiers awaiting review, oldest first.
CREATE INDEX idx_applications_pool
    ON applications (session_id, status, submitted_at)
    WHERE claimed_by IS NULL;

-- A reviewer's own workload.
CREATE INDEX idx_applications_claimed
    ON applications (claimed_by)
    WHERE claimed_by IS NOT NULL;

-- ── 2. typed rejection grounds ─────────────────────────────────────
-- A free-text justification records WHAT was said; the ground records WHY
-- in a form the system can reason about — which matters because one ground
-- is legally constrained.
--
-- In the French administrative tradition, from which Mauritanian
-- administrative law derives, an authority may not reject a file for
-- INCOMPLETENESS without first inviting the applicant to complete it
-- (cf. CRPA art. L. 114-5). So INCOMPLETE_FILE is refused by the service
-- unless a correction round has already been offered and unanswered.
-- Substantive grounds carry no such duty.
ALTER TABLE review_decisions
    ADD COLUMN rejection_ground VARCHAR(30);

ALTER TABLE review_decisions
    ADD CONSTRAINT review_decision_ground_valid
        CHECK (rejection_ground IS NULL OR rejection_ground IN (
            'INCOMPLETE_FILE',      -- documentary deficiency
            'INELIGIBLE',           -- does not meet the profession's criteria
            'FRAUDULENT_DOCUMENT',  -- falsified or altered evidence
            'WRONG_CATEGORY',       -- applied under the wrong category
            'OTHER'                 -- anything else; free text is mandatory
        ));

-- A ground belongs to a rejection, and a rejection must carry one.
ALTER TABLE review_decisions
    ADD CONSTRAINT review_decision_ground_matches_decision
        CHECK ((decision = 'REJECT' AND rejection_ground IS NOT NULL)
            OR (decision <> 'REJECT' AND rejection_ground IS NULL));

COMMENT ON COLUMN review_decisions.rejection_ground IS
    'Why the file was rejected, in a form the system can reason about. '
    'INCOMPLETE_FILE requires a prior correction round.';

-- ── 3. the decision must name its author ───────────────────────────
-- Belt and braces with the service: whoever decided is the person who
-- answers for it if the decision is challenged.
ALTER TABLE review_decisions
    ALTER COLUMN reviewer_id SET NOT NULL;
