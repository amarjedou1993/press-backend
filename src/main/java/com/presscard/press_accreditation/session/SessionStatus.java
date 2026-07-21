package com.presscard.press_accreditation.session;

import java.util.Optional;

/**
 * The manual phase machine (V1.3 §F). Transitions are linear and
 * admin-triggered — never automatic on date. next() encodes the only legal
 * forward move from each state; CLOSED is terminal.
 *
 *   PLANNED → RECEIVING → REVIEW → CORRECTION → RECLAMATION → CLOSED
 */
public enum SessionStatus {
    PLANNED,
    RECEIVING,
    REVIEW,
    CORRECTION,
    RECLAMATION,
    CLOSED;

    /** The next phase, or empty if already CLOSED (nothing follows). */
    public Optional<SessionStatus> next() {
        return switch (this) {
            case PLANNED     -> Optional.of(RECEIVING);
            case RECEIVING   -> Optional.of(REVIEW);
            case REVIEW      -> Optional.of(CORRECTION);
            case CORRECTION  -> Optional.of(RECLAMATION);
            case RECLAMATION -> Optional.of(CLOSED);
            case CLOSED      -> Optional.empty();
        };
    }

    /** Candidates may submit only while the session is receiving. */
    public boolean acceptsSubmissions() {
        return this == RECEIVING;
    }
}
