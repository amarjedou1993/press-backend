-- ═══════════════════════════════════════════════════════════════════
-- RENEWING AN INSTITUTIONAL CARD.
--
-- ⚠️ THE INSTITUTION RE-FILES; THE MINISTRY GRANTS. AS BEFORE.
--
-- A C card asserts one thing: this person is a journalist employed by this
-- body. Two years later that is exactly what may have stopped being true —
-- and the body employing them is the only party who knows.
--
-- So a renewal is a fresh filing, not a button. The institution supplies its
-- current roll; whoever is absent from it is simply not renewed, and nobody
-- has to remember to withdraw them. What is new here is only the CHAIN
-- between the two cards, and the retirement of the first.
--
-- ⚠️ AND THE SHAPE IS THE PRESS CARD'S, deliberately: cards.renewed_from_card_id
-- does the same work for series A. Two mechanisms answering "which card
-- replaced which" would eventually disagree, and the register's whole value
-- is that it does not.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE institutional_cards
    ADD COLUMN IF NOT EXISTS renewed_from_card_id BIGINT
        REFERENCES institutional_cards(id) ON DELETE SET NULL;

-- ⚠️ ON DELETE SET NULL, not CASCADE. Removing an old card must never take
-- the card that replaced it: the new one is in somebody's pocket.

COMMENT ON COLUMN institutional_cards.renewed_from_card_id IS
    'The card this one replaces. Makes "is this person still accredited here, '
    'or did their card merely lapse" a query rather than a telephone call — '
    'and keeps an accreditation readable years later, when the holder has '
    'three numbers behind them.';

-- ⚠️ ONE RENEWAL PER CARD.
--
-- Two cards claiming to replace the same predecessor means two live
-- credentials from one accreditation, and no way to say which the register
-- means. The same partial index guards series A.
CREATE UNIQUE INDEX IF NOT EXISTS uq_institutional_one_renewal_per_card
    ON institutional_cards (renewed_from_card_id)
    WHERE renewed_from_card_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_institutional_renewed_from
    ON institutional_cards (renewed_from_card_id)
    WHERE renewed_from_card_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════
-- ⚠️ uq_institutional_live_holder IS NOT TOUCHED, AND THAT TOOK A MOMENT.
--
-- It reads:
--
--     UNIQUE (institution_id, identity_number)
--     WHERE granted_at IS NOT NULL AND status <> 'REVOKED'
--
-- A renewal means the same person holding a card at the same body twice —
-- so the obvious move was to widen the index to exclude superseded cards.
--
-- That cannot be written: PostgreSQL forbids a subquery in an index
-- predicate, and "has a successor" is a fact about ANOTHER row. An attempt
-- would fail at migration time, which is at least loud.
--
-- ⚠️ THE ORDER OF OPERATIONS SOLVES IT INSTEAD, AND BETTER.
--
-- The renewal is filed days earlier with granted_at NULL — invisible to this
-- index. At the moment of granting, InstitutionalCardService RETIRES THE
-- PREDECESSOR FIRST, inside the same transaction, and only then writes the
-- new card's grant fields.
--
-- The old row is REVOKED before the new one becomes granted, so the two never
-- coexist under the index's predicate. No schema change, no widened rule, and
-- the constraint stays exactly as strict as it was: ONE LIVE CARD PER PERSON
-- PER BODY, enforced by the database rather than by sequencing alone.
--
-- ⚠️ THE SEQUENCE IS LOAD-BEARING. If a future edit grants before retiring,
-- this index refuses the insert with "duplicate key value violates unique
-- constraint" — on the one operation the feature exists to perform. The
-- service says so where the order is written.
-- ═══════════════════════════════════════════════════════════════════
