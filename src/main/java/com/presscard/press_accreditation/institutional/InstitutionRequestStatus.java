package com.presscard.press_accreditation.institutional;

/**
 * Where a request stands.
 *
 * SUBMITTED → PENDING_REVIEW → APPROVED | REJECTED
 *     └────────────────────────→ EXPIRED   (address never confirmed)
 *
 * ⚠️ The Ministry only ever sees PENDING_REVIEW. A request whose address
 * nobody has confirmed is not worth a reviewer's time — it may have been
 * typed by anyone, with anyone's address.
 */
public enum InstitutionRequestStatus {
    SUBMITTED,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    EXPIRED
}
