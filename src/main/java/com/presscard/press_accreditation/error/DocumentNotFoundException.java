package com.presscard.press_accreditation.error;

/** Document id unknown, or not part of the given application. → 404 */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(Long id) {
        super("Document introuvable : " + id);
    }
}
