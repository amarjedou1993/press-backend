-- V<N>__add_preferred_locale.sql
--
-- Which language each person reads.
--
-- ⚠️ WHY THIS COLUMN EXISTS AT ALL.
--
-- Every translation in the interface reads the locale from the REQUEST: a
-- person is looking at a page, and the framework knows which one they chose.
--
-- An e-mail has no request. It is composed hours later by a scheduled job and
-- sent to somebody who is not there. Without a stored preference the system
-- must guess, and it would guess French for every Arabic-reading journalist
-- in the country.
--
-- DEFAULT 'ar' rather than 'fr': Arabic is the official language and the
-- public interface defaults to it, so an account created before this column
-- existed is more likely Arabic-reading than not. Anyone for whom that is
-- wrong changes it from their profile in one click — and every notification
-- carries a line saying so.
--
-- Two characters, CHECKed: this is an ISO 639-1 code, not free text, and a
-- typo here would silently send someone mail in a language that does not
-- exist.

ALTER TABLE users
    ADD COLUMN preferred_locale VARCHAR(2) NOT NULL DEFAULT 'ar';

ALTER TABLE users
    ADD CONSTRAINT users_preferred_locale_valid
        CHECK (preferred_locale IN ('ar', 'fr'));

COMMENT ON COLUMN users.preferred_locale IS
    'ISO 639-1 code for the language this person reads. Set at registration '
    'from the interface locale; changeable from the profile. Used for e-mail, '
    'which has no request context to read a locale from.';
