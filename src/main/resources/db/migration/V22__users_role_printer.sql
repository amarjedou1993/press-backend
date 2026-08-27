-- V22__users_role_printer.sql
--
-- PRINTER joins the roles the users table accepts.
--
-- ⚠️ A CHECK CONSTRAINT IS A SWITCH THE COMPILER CANNOT SEE.
--
-- Adding a constant to UserRole was checked against Java switches and against
-- the TypeScript union. Nothing checks it against the database — so the enum
-- grew, every layer compiled, and the value was refused at the last possible
-- moment, by a constraint written months earlier.
--
-- Every future role needs this file's twin. There is no way to be reminded.

ALTER TABLE users DROP CONSTRAINT users_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_role_check
        CHECK (role IN ('CANDIDATE', 'REVIEWER', 'PRINTER', 'SUPER_ADMIN'));