package com.presscard.press_accreditation.error;

/**
 * This candidate may not renew.
 *
 * ⚠️ The message is a FRENCH SENTENCE, not a key — it names a concrete
 * situation ("cette carte a déjà été renouvelée", "aucune session de
 * renouvellement n'est ouverte") and it reaches the candidate space, which
 * is bilingual.
 *
 * ⚠️ THAT IS A KNOWN INCONSISTENCY. The candidate space resolves keys; these
 * sentences will pass through untranslated into an Arabic screen, exactly as
 * InvalidFileException's do. Both belong on the same list — the fix is the
 * same shape for both, and doing one without the other would be half a
 * convention.
 */
public class NotEligibleForRenewalException extends RuntimeException {
    public NotEligibleForRenewalException(String message) {
        super(message);
    }
}