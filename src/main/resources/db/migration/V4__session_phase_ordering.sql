-- ═══════════════════════════════════════════════════════════════════
-- V4__session_phase_ordering.sql
-- Relax the ordering constraint for Option A semantics.
--
-- WHY: under Option A a phase's end date records when it ACTUALLY closed.
-- An admin may open and close a phase on the same day (common in testing,
-- legitimate in practice for a phase with nothing pending), which makes
-- consecutive boundaries EQUAL. The original constraint required
-- start_date < receiving_end strictly, so a same-day receiving phase was
-- rejected by the database.
--
-- The meaningful invariant is that the calendar never runs BACKWARDS —
-- expressed with <= throughout.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE sessions DROP CONSTRAINT IF EXISTS session_phases_ordered;

ALTER TABLE sessions
    ADD CONSTRAINT session_phases_ordered
        CHECK (start_date      <= receiving_end
           AND receiving_end   <= review_end
           AND review_end      <= correction_end
           AND correction_end  <= reclamation_end);

COMMENT ON CONSTRAINT session_phases_ordered ON sessions IS
    'The calendar never runs backwards. Equality is allowed: a phase opened '
    'and closed on the same day leaves consecutive boundaries equal.';
