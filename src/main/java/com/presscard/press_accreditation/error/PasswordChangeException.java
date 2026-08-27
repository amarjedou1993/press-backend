package com.presscard.press_accreditation.error;
/**
 * A password change that cannot proceed for a reason the caller can fix.
 *
 * ⚠️ NOT a wrong current password — that is a 401, returned directly by the
 * controller, because it is the same failure as a failed login and must read
 * the same way.
 *
 * This covers the two cases where the request is well-formed and
 * authenticated but the change still makes no sense: an account with no local
 * password to change, and a new password identical to the old one.
 *
 * ⚠️ THE MESSAGE IS A KEY, NOT A SENTENCE. It reaches the screen as
 * ProblemDetail.detail and is resolved there against the reader's catalogue.
 * An unrecognised string passes through unchanged — so a French sentence here
 * would simply appear in French inside an Arabic dialog, silently.
 */
public class PasswordChangeException extends RuntimeException {

    public PasswordChangeException(String messageKey) {
        super(messageKey);
    }
}
