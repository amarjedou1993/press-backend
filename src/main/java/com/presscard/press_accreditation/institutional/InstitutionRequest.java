package com.presscard.press_accreditation.institutional;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A body asking to be registered as an institution.
 *
 * ⚠️ NOT AN ACCOUNT. Nothing here can sign in, file staff or appear in the
 * register. Only the Ministry's approval turns it into an Institution and a
 * user — see InstitutionRequestService.approve.
 */
@Entity
@Table(name = "institution_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstitutionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposed_name_fr", nullable = false, length = 200)
    private String proposedNameFr;

    @Column(name = "proposed_name_ar", nullable = false, length = 200)
    private String proposedNameAr;

    @Column(name = "contact_name", nullable = false, length = 200)
    private String contactName;

    @Column(name = "contact_role", nullable = false, length = 200)
    private String contactRole;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(nullable = false, length = 5)
    @Builder.Default
    private String locale = "fr";

    /** Hashed at submission; cleared once the decision no longer needs it. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "letter_path", nullable = false, length = 500)
    private String letterPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InstitutionRequestStatus status = InstitutionRequestStatus.SUBMITTED;

    @Column(name = "verification_token_hash", length = 64, unique = true)
    private String verificationTokenHash;

    @Column(name = "verification_expires_at")
    private OffsetDateTime verificationExpiresAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason", columnDefinition = "text")
    private String decisionReason;

    @Column(name = "institution_id")
    private Long institutionId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
