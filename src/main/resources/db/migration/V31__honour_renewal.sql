-- ═══════════════════════════════════════════════════════════════════
-- RENEWING AN HONOUR CARD.
--
-- ⚠️ THE CHAIN, AND NOTHING ELSE.
--
-- The same shape as cards.renewed_from_card_id and
-- institutional_cards.renewed_from_card_id. Three mechanisms answering
-- "which card replaced which" would eventually disagree, and the register's
-- whole value is that it does not.
--
-- Without it, a lapsed honour card and a withdrawn one look identical in the
-- register — and "is this person still honoured, or did their card simply run
-- out?" becomes a telephone call.
--
-- ⚠️ AND IT IS SMALLER THAN THE OTHER TWO, deliberately. A press card is
-- renewed through a dossier because a journalist's activity may have changed;
-- an institutional card through a re-filing because employment may have. An
-- honour card has no such fact to re-establish: the Ministry decides whether
-- the distinction continues, and that decision is one click.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE honour_cards
    ADD COLUMN IF NOT EXISTS renewed_from_card_id BIGINT
        REFERENCES honour_cards(id) ON DELETE SET NULL;

-- ⚠️ ON DELETE SET NULL, not CASCADE. Removing an old card must never take
-- the card that replaced it: the new one is in somebody's pocket.

COMMENT ON COLUMN honour_cards.renewed_from_card_id IS
    'The card this one replaces. NULL for a first grant.';

-- ⚠️ ONE RENEWAL PER CARD. Two cards claiming to replace the same
-- predecessor would be two live distinctions from one decision.
CREATE UNIQUE INDEX IF NOT EXISTS uq_honour_one_renewal_per_card
    ON honour_cards (renewed_from_card_id)
    WHERE renewed_from_card_id IS NOT NULL;
