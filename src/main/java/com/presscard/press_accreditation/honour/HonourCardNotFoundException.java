package com.presscard.press_accreditation.honour;

/** No honour card with this identifier. */
public class HonourCardNotFoundException extends RuntimeException {
    public HonourCardNotFoundException(Long id) {
        super("Carte d'honneur introuvable : " + id);
    }
}
