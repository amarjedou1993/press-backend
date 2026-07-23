package com.presscard.press_accreditation.admin;

/** Reviewer id doesn't exist (or the account isn't a reviewer) → 404. */
public class ReviewerNotFoundException extends RuntimeException {
    public ReviewerNotFoundException(Long id) {
        super("Reviewer not found: " + id);
    }
}
