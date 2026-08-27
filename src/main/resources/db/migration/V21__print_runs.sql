-- V<N>__print_runs.sql
--
-- What left the building, when, and at whose hand.
--
-- ───────────────────────────────────────────────────────────────────────
-- ⚠️ A RECORD, NOT A GATE.
--
-- Nothing here prevents a second production run. A blanket permission gate on
-- an external contractor is a control that gets worked around — someone
-- e-mails a file, and the reprint vanishes from the record entirely. That is
-- worse than no gate: the appearance of control with none of the substance.
--
-- So reprints are free and every one is written down. A card produced eleven
-- times is a question somebody asks, not an error somebody meets.
-- ───────────────────────────────────────────────────────────────────────

CREATE TABLE print_runs (
    id           BIGSERIAL PRIMARY KEY,

    printed_by   BIGINT      NOT NULL REFERENCES users(id),
    printed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Null when a run spans sessions, which an administrator's PDF batch can.
    session_id   BIGINT      REFERENCES sessions(id),

    /*
     * ⚠️ WHAT ACTUALLY LEFT.
     *
     * ASSETS — the production archive: photograph, verification QR, reference
     *          preview. What the PRINTER receives.
     * PDF    — the signed, laid-out card. Administrators only.
     *
     * They are not interchangeable. The printer never holds the signed
     * document, so the Ministry's layout and signature stay inside; a history
     * that conflated the two would say the printer had it.
     */
    kind         VARCHAR(20) NOT NULL,

    -- Only meaningful for a PDF run; null for assets.
    layout       VARCHAR(20),

    card_count   INTEGER     NOT NULL,

    CONSTRAINT print_runs_kind_valid
        CHECK (kind IN ('ASSETS', 'PDF'))
);

/*
 * ⚠️ A RUN, AND THE CARDS IN IT — not a row per card.
 *
 * A printer thinks in batches: "the 47 on 12 March". Forty-seven separate
 * lines is a list nobody reads.
 *
 * But the per-card fact has to survive, because the question that gets asked
 * is "was Mr Fall's card in that batch?" — and that is what this table
 * answers.
 */
CREATE TABLE print_run_cards (
    run_id   BIGINT NOT NULL REFERENCES print_runs(id) ON DELETE CASCADE,
    card_id  BIGINT NOT NULL REFERENCES cards(id),
    PRIMARY KEY (run_id, card_id)
);

-- The printer's own history, newest first.
CREATE INDEX print_runs_by_actor ON print_runs (printed_by, printed_at DESC);

-- "How many times has this card been produced?" — the admin's question.
CREATE INDEX print_run_cards_by_card ON print_run_cards (card_id);

COMMENT ON TABLE print_runs IS
    'Production runs. Records what left the building; does not restrict it. '
    'Reprints are deliberately unrestricted — see the migration header.';
