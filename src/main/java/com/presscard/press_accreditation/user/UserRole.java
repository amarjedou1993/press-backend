//package com.presscard.press_accreditation.user;
//
///**
// * The three V1.3 roles. Values must match the CHECK constraint in V1__init.sql
// * exactly — the enum and the database constraint are one contract in two places.
// */
//public enum UserRole {
//    CANDIDATE,
//    REVIEWER,
//    SUPER_ADMIN
//}

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
 * The four are deliberately flat. There is no hierarchy: SUPER_ADMIN does not
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

    /** The Ministry. */
    SUPER_ADMIN
}
