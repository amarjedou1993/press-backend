-- ═══════════════════════════════════════════════════════════════════
-- V3__session_phase_tracking.sql
-- Option A: per-phase DURATIONS are guaranteed; the calendar re-bases
-- when a phase closes early or late.
--
-- Until now a session stored only the DERIVED boundary dates plus the
-- total — a plan computed once at creation. Because phases advance
-- manually (V1.3 §F), reality diverges from that plan the moment an
-- admin closes a phase early. To re-base we must know two things the
-- schema never kept:
--   · how many days each phase was ALLOTTED (to re-apply it)
--   · when the CURRENT phase actually started (to count from)
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE sessions
    ADD COLUMN receiving_days   INT,
    ADD COLUMN review_days      INT,
    ADD COLUMN correction_days  INT,
    ADD COLUMN reclamation_days INT,
    ADD COLUMN phase_started_at DATE;

-- Backfill existing rows from their stored boundary dates (the plan they
-- were created with), so nothing is left null before the NOT NULL clamp.
UPDATE sessions SET
    receiving_days   = (receiving_end   - start_date),
    review_days      = (review_end      - receiving_end),
    correction_days  = (correction_end  - review_end),
    reclamation_days = (reclamation_end - correction_end),
    phase_started_at = COALESCE(phase_started_at, start_date)
WHERE receiving_days IS NULL;

ALTER TABLE sessions
    ALTER COLUMN receiving_days   SET NOT NULL,
    ALTER COLUMN review_days      SET NOT NULL,
    ALTER COLUMN correction_days  SET NOT NULL,
    ALTER COLUMN correction_days  SET NOT NULL,
    ALTER COLUMN reclamation_days SET NOT NULL,
    ALTER COLUMN phase_started_at SET NOT NULL;

ALTER TABLE sessions
    ADD CONSTRAINT session_phase_days_positive
        CHECK (receiving_days > 0 AND review_days > 0
           AND correction_days > 0 AND reclamation_days > 0);

-- The original constraint asserted the plan summed exactly to total_days.
-- Under Option A the calendar shifts on every transition, so that identity
-- no longer holds. The ORDERING constraint (session_phases_ordered) stays —
-- it is what actually guarantees a coherent calendar.
ALTER TABLE sessions
    DROP CONSTRAINT IF EXISTS session_phases_sum_to_total;

COMMENT ON COLUMN sessions.phase_started_at IS
    'Date the CURRENT phase actually began (set on every manual transition). '
    'Remaining boundary dates are re-derived from it, so each phase always '
    'receives its full allotted duration.';
