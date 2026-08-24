package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An issued press card.*
 * The row carries the card's CURRENT state; every status change is a row in
 * card_status_history. The same shape as applications, for the same reason:
 * for a regulator the audit trail is the product.*
 * ── EVERYTHING PRINTED ON THE CARD IS A SNAPSHOT ── *
 * photoPath, specialization and institution are COPIED at issuance, never
 * referenced live. A card is a dated document: if the holder moves to another
 * outlet in 2027, or changes their profile photograph, the 2026 card must
 * still say what it said when it was issued.*
 * The alternative — reading them from the application at print time — would
 * mean a reprint of a two-year-old card silently produced a DIFFERENT
 * document from the one originally delivered. For a credential, that is not a
 * refresh; it is a forgery committed by the system itself.*
 * NOTE — THERE IS NO created_at HERE, deliberately. On an application,
 * drafting and submitting are different moments, so a row timestamp says
 * something the business dates do not. A card has no such gap: `issuedAt` IS
 * its creation event, `printedAt` covers production of the artifact, and
 * card_status_history covers everything afterward.
 */
@Entity
@Table(name = "cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, unique = true)
    private Long applicationId;

    /** A - 0001 / 26 — series letter, four-digit sequence, two-digit year. */
    @Column(name = "card_number", nullable = false, unique = true, length = 30)
    private String cardNumber;

    @Column(name = "issued_at", nullable = false)
    private LocalDate issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDate expiresAt;

    /* ── what is printed: all snapshots ── */

    /** The photograph AS ISSUED — immutable once the card exists. */
    @Column(name = "photo_path", length = 500)
    private String photoPath;

    /** التخصص, as printed. The holder may have changed role since. */
    @Column(name = "specialisation_fr", length = 120)
    private String specialisationFr;

    @Column(name = "specialisation_ar", length = 120)
    private String specialisationAr;

    /** المؤسسة, as printed. The holder may have changed outlet since. */
    @Column(length = 200)
    private String institution;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    /* ── verification ── */

    /**
     * Opaque and random, never derived from the card number: a QR reading
     * /verifier/A-0042-26 would let anyone iterate the range and harvest every
     * accredited journalist's identity and photograph.
     */
    @Column(name = "verification_token", unique = true, length = 32)
    private String verificationToken;

    /** Ed25519 over the canonical card string — HAPA's evidence of issuance. */
    @Column(columnDefinition = "text")
    private String signature;

    /** Which key signed it: cards outlive key rotations. */
    @Column(name = "signature_key_id", length = 40)
    private String signatureKeyId;

    /* ── lifecycle ── */

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CardStatus status = CardStatus.VALID;

    @Column(name = "status_changed_at")
    private OffsetDateTime statusChangedAt;

    @Column(name = "status_changed_by")
    private Long statusChangedBy;

    @Column(name = "status_reason", columnDefinition = "text")
    private String statusReason;

    /* ── issuance ── */

    @Column(name = "issued_by")
    private Long issuedBy;

    @Column(name = "printed_at")
    private OffsetDateTime printedAt;

    /** A reprint is the same accreditation, a new artifact. */
    @Column(name = "print_count", nullable = false)
    @Builder.Default
    private int printCount = 0;

    /**
     * Times this card's assets were exported for production.
     *
     * ⚠️ NOT printCount. That one means "a PDF was generated for the
     * printer"; this one means "a designer collected the material", possibly
     * several times while iterating a layout. Merged, neither number would
     * answer its own question.
     */
    @Column(name = "archive_count", nullable = false)
    @Builder.Default
    private int archiveCount = 0;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /* ── derived ── */

    /**
     * Expiry is a DATE FACT, computed on every read rather than stored. No job
     * can forget to run it, and no lapsed card can ever read "valid".
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDate.now().isAfter(expiresAt);
    }

    /** What a verifier actually needs to know. */
    public boolean isUsable() {
        return status.isInForce() && !isExpired();
    }
}
