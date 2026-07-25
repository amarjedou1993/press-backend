package com.presscard.press_accreditation.error;

import com.presscard.press_accreditation.application.SubmissionGate;

import java.util.List;

/**
 * Submission refused by the gate. Carries EVERY unmet condition — the
 * candidate should be able to fix everything in one pass rather than
 * discovering problems one at a time. → 422
 */
public class SubmissionRefusedException extends RuntimeException {

    private final transient List<SubmissionGate.Blocker> blockers;

    public SubmissionRefusedException(List<SubmissionGate.Blocker> blockers) {
        super("Le dossier ne peut pas être soumis.");
        this.blockers = blockers;
    }

    public List<SubmissionGate.Blocker> getBlockers() {
        return blockers;
    }
}
