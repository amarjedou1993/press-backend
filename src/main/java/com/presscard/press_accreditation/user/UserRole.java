package com.presscard.press_accreditation.user;

/**
 * Who a person is to this system.
 *
 * ⚠️ ADDING A CONSTANT HERE CHANGES EVERY PLACE THAT READS ONE.
 *
 * Java tells you about exhaustive switches. It says nothing about a switch
 * with a `default`, which silently takes the wrong branch — homeForRole on
 * the frontend does exactly that, and a PRINTER would land in the candidate
 * space with nothing failing.
 *
 * ⚠️ AND IT HAPPENED AGAIN WITH INSTITUTION. The same `default`, the same
 * silent landing in the candidate dashboard, found only because the comment
 * above had recorded the first occurrence. The case is added; the warning
 * stands for whatever comes next.
 *
 * The five are deliberately flat. There is no hierarchy: SUPER_ADMIN does not
 * inherit REVIEWER, because an administrator who could also decide cases
 * would break the different-reviewer rule the objection right depends on.
 * Where an administrator needs a role's access, it is granted explicitly.
 */
public enum UserRole {

    /** A journalist applying for, or holding, a card. */
    CANDIDATE,

    /** A member of the examining commission. */
    REVIEWER,

    /**
     * Produces the physical cards.
     *
     * ⚠️ TYPICALLY EXTERNAL to the Ministry, and the role is built on that
     * assumption: it reaches the production assets — photograph, verification
     * QR, reference preview — and never the signed card PDF, which carries
     * the Ministry's layout and its signature.
     *
     * An account revoked in one click, rather than a person who holds the
     * files. That is what the role is for.
     */
    PRINTER,

    /**
     * A body that files its own journalists — HAPA today, others possibly
     * later.
     *
     * ⚠️ THE ACCOUNT BELONGS TO THE INSTITUTION, NOT TO A PERSON.
     *
     * Staff turnover must not cost an institution its access, so the login is
     * the organisation's. users.institution_id carries which body it speaks
     * for, and a CHECK constraint refuses an INSTITUTION account without one —
     * or any other role with one.
     *
     * ⚠️ AND IT FILES; IT DOES NOT ISSUE. An institution declares that a
     * person works there. The Ministry grants the card, takes the C number and
     * signs it — because a press card is the Ministry's document even when no
     * commission examined it, and a body issuing credentials to its own staff
     * would be issuing them to itself.
     *
     * The consequence for this enum: an INSTITUTION account reaches only
     * /api/institution/**, and every method there resolves its institution
     * from the account rather than from the request. A leaked account is a
     * leaked organisation, not a leaked person.
     */
    INSTITUTION,

    /** The Ministry. */
    SUPER_ADMIN
}