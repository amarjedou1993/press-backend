package com.presscard.press_accreditation.error;

/**
 * The application does not exist — OR it exists but belongs to someone else.
 * Deliberately the same exception for both: telling a stranger that an
 * application exists is already a disclosure. → 404
 */
public class ApplicationNotFoundException extends RuntimeException {
    public ApplicationNotFoundException(Long id) {
        super("Candidature introuvable : " + id);
    }
}
