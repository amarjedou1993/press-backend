package com.presscard.press_accreditation.error;

/**
 * A print batch was requested with a layout its cards cannot support.
 *
 * Currently one case: SHARED_BACK across cards with different expiry dates.
 * One common back would print the wrong date on part of the run — and unlike
 * a mis-ordered PDF, that is not visible until someone reads a card months
 * later.
 * → 409
 */
public class IncompatibleBatchException extends RuntimeException {
    public IncompatibleBatchException(String message) { super(message); }
}
