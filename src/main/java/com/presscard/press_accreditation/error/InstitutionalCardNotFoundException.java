package com.presscard.press_accreditation.error;

public class InstitutionalCardNotFoundException extends RuntimeException {
    public InstitutionalCardNotFoundException(Long id) {
        super("Fiche institutionnelle introuvable : " + id);
    }
}