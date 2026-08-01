package com.presscard.press_accreditation.error;

/**
 * A card was requested for a dossier that cannot produce one: not accepted,
 * no photograph, or no profile. → 409
 */
public class CardNotIssuableException extends RuntimeException {
    public CardNotIssuableException(String message) { super(message); }
}
