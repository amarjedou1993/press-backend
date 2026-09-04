package com.presscard.press_accreditation.honour;

/**
 * The archive itself cannot be read.
 *
 * ⚠️ DISTINCT FROM A ROW ERROR. A malformed row is reported and skipped; this
 * means nothing could be parsed at all — no workbook, an unreadable file, no
 * header. The message is a French sentence rather than a key, like the rest of
 * the administration space.
 */
public class HonourImportException extends RuntimeException {
    public HonourImportException(String message) {
        super(message);
    }
}
