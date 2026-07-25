package com.presscard.press_accreditation.error;

/** Upload rejected: empty, too large, wrong MIME type, or wrong kind. → 400 */
public class InvalidFileException extends RuntimeException {
    public InvalidFileException(String message) {
        super(message);
    }
}
