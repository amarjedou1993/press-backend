package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.CardStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A card granted by the Ministry without a candidacy.
 *
 * ⚠️ THE FIELDS DUPLICATE `Card`, AND THAT IS THE PRICE OF THE SEPARATION.
 *
 * What differs is not the content but everything around it: an ordinary card
 * has a dossier, a session, a commission decision, a correction round, an
 * objection right and a cohort expiry. This has none of them. Merged into one
 * table, half that lifecycle would be nullable and every query would have to
 * remember which kind it was holding.
 *
 * ⚠️ AND UNLIKE Card, THIS IS SELF-CONTAINED. Card reaches its holder's name
 * through card → application → user, which is why editing a profile changes
 * the PDF of a card already issued. Nothing here is reached; the row is the
 * card.
 */
@Entity
@Table(name = "honour_cards")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HonourCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** B - 0001 / 26 — its own series, its own sequence. */
    @Column(name = "card_number", nullable = false, unique = true, length = 30)
    private String cardNumber;

    /* ── the holder ── */

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /**
     * NNI or passport.
     *
     * ⚠️ NOT OPTIONAL: the signature is computed over it. Without one the card
     * cannot be signed, and a scan reports the Ministry's own card as
     * unverifiable — which reads as forged rather than unknown.
     */
    @Column(name = "identity_number", nullable = false, length = 40)
    private String identityNumber;

    private LocalDate birthdate;

    @Column(length = 200)
    private String birthplace;

    /* ── what is printed, and what a verifier reads ── */

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "specialisation_id")
    private Long specialisationId;

    @Column(length = 200)
    private String institution;

    @Column(name = "photo_path", length = 500)
    private String photoPath;

    /* ── validity ── */

    @Column(name = "issued_at", nullable = false)
    private LocalDate issuedAt;

    /**
     * ⚠️ Set by the Ministry, card by card.
     *
     * An ordinary card takes its expiry from its session, so a whole cohort
     * renews together. There is no session here — nothing can derive this and
     * nothing should guess it.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDate expiresAt;

    /* ── lifecycle ── */

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CardStatus status = CardStatus.VALID;

    @Column(name = "status_reason", columnDefinition = "text")
    private String statusReason;

    @Column(name = "status_changed_at")
    private OffsetDateTime statusChangedAt;

    @Column(name = "status_changed_by")
    private Long statusChangedBy;

    /* ── verification ── */

    /**
     * Opaque and random, never derived from the card number: a QR reading
     * /verifier/B-0042-26 would let anyone iterate the range and harvest every
     * holder's identity and photograph.
     */
    @Column(name = "verification_token", nullable = false, unique = true, length = 64)
    private String verificationToken;

    @Column(columnDefinition = "text")
    private String signature;

    @Column(name = "signature_key_id", length = 40)
    private String signatureKeyId;

    /* ── the grant ── */

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    /**
     * ⚠️ MANDATORY, for the reason a justification is mandatory on a
     * rejection: this card bypasses the examination every other card requires,
     * and the record must say on whose authority and why.
     */
    @Column(name = "grant_reason", nullable = false, columnDefinition = "text")
    private String grantReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /* ── derived ── */

    /**
     * Expiry is a DATE FACT, computed on every read rather than stored — the
     * same rule as Card. No job can forget to run it, and no lapsed card can
     * ever read "valide".
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDate.now().isAfter(expiresAt);
    }

    /** What a verifier actually needs to know. */
    public boolean isUsable() {
        return status.isInForce() && !isExpired();
    }
}
