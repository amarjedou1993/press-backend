-- -- V<N>__email_outbox_locale.sql
-- --
-- -- Which language a queued message is written in.
-- --
-- -- ⚠️ THE LOCALE IS RESOLVED WHEN THE MESSAGE IS QUEUED, NOT WHEN IT IS SENT.
-- --
-- -- The outbox is drained by a scheduled job, possibly minutes or hours later,
-- -- and possibly after the holder has changed their preference. The language a
-- -- message is written in should be the one in force WHEN THE EVENT HAPPENED —
-- -- otherwise a decision taken while someone read French could arrive in Arabic
-- -- because they switched in the meantime, and a retry could differ from the
-- -- first attempt.
-- --
-- -- DEFAULT 'fr': existing rows are staff notifications and pre-bilingual
-- -- candidate mail, all of which were composed in French.
--
-- ALTER TABLE email_outbox
--     ADD COLUMN locale VARCHAR(2) NOT NULL DEFAULT 'fr';
--
-- ALTER TABLE email_outbox
--     ADD CONSTRAINT email_outbox_locale_valid
--         CHECK (locale IN ('ar', 'fr'));
--
-- COMMENT ON COLUMN email_outbox.locale IS
--     'ISO 639-1 code the message body is rendered in. Fixed at queue time from '
--     'the recipient''s preferred_locale, so a retry reproduces the first '
--     'attempt and a later preference change does not rewrite history.';

-- V19__email_outbox_locale.sql
--
-- Which language a queued message is written in.
--
-- The locale is resolved when the message is queued, not when it is sent.
-- This ensures retries reproduce the original language even if the recipient
-- changes their language preference later.
--
-- Existing/pre-bilingual messages default to French.

ALTER TABLE email_outbox
    ADD COLUMN IF NOT EXISTS locale VARCHAR(2);

-- Existing rows predate locale support and were composed in French.
UPDATE email_outbox
SET locale = 'fr'
WHERE locale IS NULL;

-- New rows default to French unless the application explicitly chooses
-- another supported locale.
ALTER TABLE email_outbox
    ALTER COLUMN locale SET DEFAULT 'fr';

-- All existing NULL values have been backfilled above, so NOT NULL can now
-- safely be enforced.
ALTER TABLE email_outbox
    ALTER COLUMN locale SET NOT NULL;

-- Make rerunning/repairing a partially-created development schema safe.
ALTER TABLE email_outbox
DROP CONSTRAINT IF EXISTS email_outbox_locale_valid;

ALTER TABLE email_outbox
    ADD CONSTRAINT email_outbox_locale_valid
        CHECK (locale IN ('ar', 'fr'));

COMMENT ON COLUMN email_outbox.locale IS
    'ISO 639-1 code the message body is rendered in. Fixed at queue time from the recipient''s preferred_locale, so a retry reproduces the first attempt and a later preference change does not rewrite history.';