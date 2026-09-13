package com.presscard.press_accreditation.error;

/**
 * An institutional filing or grant cannot proceed.
 *
 * ⚠️ THE MESSAGE IS A KEY ("validation.holderAlreadyCarded"), not a sentence.
 *
 * It reaches two spaces in two languages: the institution's, and the
 * Ministry's. HonourCardException took the same decision for the same reason,
 * and GlobalExceptionHandler already resolves keys on that path.
 */
public class InstitutionalCardException extends RuntimeException {
    public InstitutionalCardException(String messageKey) {
        super(messageKey);
    }
}