-- ═══════════════════════════════════════════════════════════════════
-- THE THIRD SERIES IN THE PRODUCTION HISTORY.
--
-- ⚠️ print_run_cards ALREADY HELD TWO PROVENANCES — an ordinary card or an
-- honour card, with a CHECK refusing a row that names both or neither. This
-- adds the third, and replaces that constraint rather than sitting beside it.
--
-- The point of one history table across three series is the question it
-- answers: "what left the building, and when". Three tables would be three
-- partial answers, and the producer's own screen already shows them together.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE print_run_cards
    ADD COLUMN IF NOT EXISTS institutional_card_id BIGINT
    REFERENCES institutional_cards(id) ON DELETE SET NULL;

-- ⚠️ ON DELETE SET NULL, not CASCADE. A card removed from the register must
-- not erase the record that it was once produced — that record is the whole
-- reason the table exists.

DROP INDEX IF EXISTS idx_print_run_cards_institutional;
CREATE INDEX idx_print_run_cards_institutional
    ON print_run_cards (institutional_card_id)
    WHERE institutional_card_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════
-- EXACTLY ONE PROVENANCE PER ROW.
--
-- ⚠️ TWO NAMES ARE DROPPED, AND THE SECOND IS THE ONE THAT MATTERED.
--
-- The honour-era constraint is called `print_run_cards_exactly_one`. An
-- earlier version of this migration dropped only the name it creates itself,
-- which found nothing — so BOTH constraints ended up in force.
--
-- The old one demands card_id or honour_card_id. An institutional row sets
-- neither, so it satisfied the new constraint and was refused by the old.
--
-- ⚠️ AND IT FAILED AT THE FIRST PRODUCTION RUN, NOT AT MIGRATION TIME.
-- Two constraints that contradict each other are only discovered by a row
-- that tries to sit between them — here, a producer pressing "Produire" and
-- reading "The request conflicts with existing data", which names nothing.
--
-- Both names are dropped because this file may meet either state: a database
-- that ran the earlier version, or a fresh one that never did.
-- ═══════════════════════════════════════════════════════════════════
ALTER TABLE print_run_cards
DROP CONSTRAINT IF EXISTS print_run_cards_one_card_check;

ALTER TABLE print_run_cards
DROP CONSTRAINT IF EXISTS print_run_cards_exactly_one;

-- ⚠️ COUNTING TO EXACTLY ONE, not merely forbidding an empty row.
--
-- A row naming an ordinary card AND an institutional one would count twice in
-- every production total — and the history's whole purpose is a number an
-- administrator will one day ask a contractor to explain.
--
-- It is also what makes the series READABLE from the row: which column is set
-- IS the answer, unambiguously, which is why print_runs.kind does not carry
-- it. See the note at the foot of this file.
ALTER TABLE print_run_cards
    ADD CONSTRAINT print_run_cards_one_card_check
        CHECK (
            (card_id IS NOT NULL)::int
    + (honour_card_id IS NOT NULL)::int
    + (institutional_card_id IS NOT NULL)::int
    = 1
    );

COMMENT ON COLUMN print_run_cards.institutional_card_id IS
    'Series C — a card granted on an institution''s filing. Exactly one of '
    'the three card columns is set, enforced by print_run_cards_one_card_check.';

-- ═══════════════════════════════════════════════════════════════════
-- ⚠️ print_runs.kind IS DELIBERATELY NOT TOUCHED.
--
-- An earlier draft of this migration widened it to CARD / HONOUR /
-- INSTITUTIONAL, on the assumption that a run's `kind` named its series. It
-- does not: PrintRun.Kind holds ASSETS and PDF, and it records HOW a card
-- left the building — assets to a producer, a signed PDF to an administrator
-- — not which series it belonged to.
--
-- Adding the series there would have put it in two places: on the run, and on
-- the card the run's row points at. The two would eventually disagree, and
-- the card is the one that is right.
--
-- The series is therefore read from WHICH COLUMN of print_run_cards is set —
-- which the CHECK above makes unambiguous, and which is why that constraint
-- counts to exactly one rather than merely forbidding an empty row.
-- ═══════════════════════════════════════════════════════════════════