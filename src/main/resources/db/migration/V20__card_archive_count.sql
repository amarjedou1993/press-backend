-- V<N>__card_archive_count.sql
--
-- How many times a card's assets have been exported for production.
--
-- ⚠️ SEPARATE FROM print_count, DELIBERATELY.
--
-- print_count means "a PDF was generated for the printer". Downloading an
-- asset archive is a different act with a different meaning — the designer
-- collecting material, possibly several times while iterating on a layout.
--
-- Folding the two together would make print_count answer neither question:
-- an administrator asking "has this card been printed?" would see a number
-- inflated by every archive download.

ALTER TABLE cards
    ADD COLUMN archive_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE cards
    ADD COLUMN archived_at TIMESTAMPTZ;

COMMENT ON COLUMN cards.archive_count IS
    'Times this card''s photo/QR/PDF assets were included in a production '
    'archive. Distinct from print_count, which counts PDF generation for '
    'printing.';
