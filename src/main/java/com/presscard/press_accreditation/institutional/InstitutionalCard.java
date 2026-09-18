package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.card.CardStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A card for an institution's own journalist.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ A FILING IS NOT A CARD. `grantedAt` IS THE LINE BETWEEN THEM.
 *
 * The institution files a person — name, identity, photograph. That row
 * exists, and it is not a credential: no number, no signature, no expiry.
 * The Ministry then grants, and those five fields are written together.
 *
 * There is no FILED/GRANTED enum beside grantedAt, deliberately. Two
 * representations of one state eventually disagree, and the one that is a
 * timestamp also says WHEN.
 * ───────────────────────────────────────────────────────────────────────
 */
@Entity
@Table(name = "institutional_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstitutionalCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    /* ── the holder, as filed ── */

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /**
     * NNI or passport.
     *
     * ⚠️ ONE COLUMN, unlike candidate_profiles which splits them. An
     * institution files its staff roll; it does not adjudicate which document
     * each employee holds, and a CHECK demanding one or the other would make
     * the import fail on a row the Ministry could simply correct.
     */
    @Column(name = "identity_number", nullable = false, length = 30)
    private String identityNumber;

    private LocalDate birthdate;

    @Column(length = 200)
    private String birthplace;

    @Column(name = "job_title", length = 200)
    private String jobTitle;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "specialisation_id")
    private Long specialisationId;

    @Column(name = "photo_path", length = 500)
    private String photoPath;

    @Column(name = "photo_uploaded_at")
    private OffsetDateTime photoUploadedAt;

    /* ── the grant ── */

    @Column(name = "card_number", length = 30)
    private String cardNumber;

    @Column(name = "verification_token", length = 64)
    private String verificationToken;

    @Column(columnDefinition = "text")
    private String signature;

    @Column(name = "signature_key_id", length = 50)
    private String signatureKeyId;

    @Column(name = "issued_at")
    private LocalDate issuedAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

    /**
     * The card this one replaces.
     *
     * ⚠️ SET AT THE FILING, not at the grant — and that is the difference
     * from a press card's renewal.
     *
     * A press card renewal is a dossier: the candidate files, the commission
     * examines, and only at issuance is the predecessor known. Here the
     * institution says at the moment of filing "this is Ahmed's next card",
     * because it is re-affirming an employment it already declared once.
     *
     * The Ministry then grants against a chain that is already established,
     * which is why the retirement below can be ordered correctly.
     */
    @Column(name = "renewed_from_card_id")
    private Long renewedFromCardId;

    /** Whether this filing replaces an existing card. */
    public boolean isRenewal() {
        return renewedFromCardId != null;
    }

    /* ── the card's life ── */

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

    /* ── provenance ── */

    @Column(name = "filed_by", nullable = false)
    private Long filedBy;

    @Column(name = "filed_at", nullable = false)
    @Builder.Default
    private OffsetDateTime filedAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    /* ── derived ── */

    /** Whether the Ministry has issued a card for this filing. */
    public boolean isGranted() {
        return grantedAt != null;
    }

    /**
     * ⚠️ COMPUTED, NEVER STORED — as on every other card in this system.
     *
     * A stored flag goes stale the day a job fails to run, and a lapsed card
     * reading "valide" at a checkpoint is the one failure none of this can
     * recover from.
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDate.now());
    }

    /** Valid, granted, and not lapsed. */
    public boolean isUsable() {
        return isGranted() && status == CardStatus.VALID && !isExpired();
    }

    /**
     * Whether a producer may make this card.
     *
     * ⚠️ A PHOTOGRAPH IS REQUIRED, for the reason it is required on an honour
     * card: a verification without a face verifies nothing. It confirms a
     * number exists, not that the person holding the card is its holder.
     */
    public boolean isProducible() {
        return isUsable() && photoPath != null;
    }
}
