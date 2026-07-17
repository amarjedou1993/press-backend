package com.presscard.press_accreditation.user;

/**
 * The three V1.3 roles. Values must match the CHECK constraint in V1__init.sql
 * exactly — the enum and the database constraint are one contract in two places.
 */
public enum UserRole {
    CANDIDATE,
    REVIEWER,
    SUPER_ADMIN
}
