package com.presscard.press_accreditation.honour;

/**
 * A grant or an edit that cannot proceed.
 *
 * ⚠️ THE MESSAGE IS A KEY, NOT A SENTENCE. It travels to the screen as
 * ProblemDetail.detail and is resolved there against the reader's catalogue.
 * An unrecognised string passes through unchanged — so a French sentence here
 * would simply appear in French in an Arabic dialog, silently.
 */
public class HonourCardException extends RuntimeException {
    public HonourCardException(String messageKey) {
        super(messageKey);
    }
}
