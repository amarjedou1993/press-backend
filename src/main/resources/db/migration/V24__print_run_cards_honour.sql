-- V<N+1>__print_run_cards_honour.sql
--
-- One production history, two kinds of card.
--
-- ───────────────────────────────────────────────────────────────────────
-- ⚠️ WHY THIS TABLE IS REBUILT ONE MIGRATION AFTER IT WAS CREATED.
--
-- print_run_cards was (run_id, card_id), both columns in the primary key,
-- card_id a foreign key to cards. An honour card cannot go in it: the key
-- points at the wrong table, and there is nowhere to put a null.
--
-- The alternatives were worse. Recording nothing would leave the production
-- record with a hole exactly where an unusual credential sits — the printer
-- produces honour cards and nobody could say so. A second history table would
-- mean two lists to merge on every screen, and two answers to "how many times
-- has this been produced".
--
-- So: a surrogate key, two nullable columns, and a constraint that exactly
-- one of them is set. Both foreign keys stay real, and there is one history.
-- ───────────────────────────────────────────────────────────────────────

/*
 * Rebuilt rather than altered: a composite primary key cannot be relaxed in
 * place, and the table is days old with little or nothing in it. Existing rows
 * are carried across — the cost is a moment's lock, not data.
 */
ALTER TABLE print_run_cards RENAME TO print_run_cards_old;

CREATE TABLE print_run_cards (
    id              BIGSERIAL PRIMARY KEY,

    run_id          BIGINT NOT NULL REFERENCES print_runs(id) ON DELETE CASCADE,

    -- Exactly one of these is set. See the constraint below.
    card_id         BIGINT REFERENCES cards(id),
    honour_card_id  BIGINT REFERENCES honour_cards(id),

    /*
     * ⚠️ THE CONSTRAINT IS THE WHOLE POINT OF THE REBUILD.
     *
     * Without it the table would accept a row naming both cards or neither —
     * and "how many times has this been produced" would count rows that name
     * nothing. The nullable columns are only safe because this makes them
     * exclusive.
     */
    CONSTRAINT print_run_cards_exactly_one CHECK (
        (card_id IS NOT NULL AND honour_card_id IS NULL)
     OR (card_id IS NULL AND honour_card_id IS NOT NULL)
    ),

    -- The old composite key, preserved as a uniqueness rule rather than an
    -- identity: a card must not appear twice in one run.
    CONSTRAINT print_run_cards_unique_card
        UNIQUE (run_id, card_id),
    CONSTRAINT print_run_cards_unique_honour
        UNIQUE (run_id, honour_card_id)
);

INSERT INTO print_run_cards (run_id, card_id)
SELECT run_id, card_id FROM print_run_cards_old;

DROP TABLE print_run_cards_old;

-- "How many times has this been produced?" — asked of both kinds.
CREATE INDEX print_run_cards_by_card ON print_run_cards (card_id);
CREATE INDEX print_run_cards_by_honour ON print_run_cards (honour_card_id);

COMMENT ON CONSTRAINT print_run_cards_exactly_one ON print_run_cards IS
    'A row names one card, of one kind. Never both, never neither.';
