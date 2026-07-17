package com.presscard.press_accreditation.user;

/**
 * Origin of the account. All V1 rows are LOCAL; KHIDMATY is the dormant
 * V2 seam (kept so the schema never needs a migration when it lands).
 */
public enum UserSource {
    LOCAL,
    KHIDMATY
}
