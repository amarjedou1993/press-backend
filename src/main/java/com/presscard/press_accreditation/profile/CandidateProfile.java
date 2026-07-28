package com.presscard.press_accreditation.profile;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Identity data for a candidate — shared across every application they ever
 * make, which is why it lives on the PERSON rather than on the application.
 * A journalist applying again next year re-uses this; nothing is re-typed.
 *
 * Maps to candidate_profiles, whose primary key IS the user id (1:0..1).
 * The database enforces candidate_identity_present: NNI or passport, at
 * least one.
 */
@Entity
@Table(name = "candidate_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** National identity number — validated by @ValidNni (modulo-97 checksum). */
    @Column(length = 20, unique = true)
    private String nni;

    @Column(name = "passport_no", length = 30)
    private String passportNo;

    @Column(nullable = false)
    private LocalDate birthdate;

    @Column(nullable = false, length = 200)
    private String birthplace;

    @Column(name = "photo_path", length = 500)
    private String photoPath;

    @Column(name = "photo_uploaded_at")
    private OffsetDateTime photoUploadedAt;

    /**
     * Whether this profile can support a submission: an identity document,
     * a birthdate and a birthplace. Mirrors the DB constraint rather than
     * inventing a second rule.
     */
    public boolean isComplete() {
        boolean hasIdentity = (nni != null && !nni.isBlank())
                || (passportNo != null && !passportNo.isBlank());
        return hasIdentity
                && birthdate != null
                && birthplace != null && !birthplace.isBlank()
                && photoPath != null && !photoPath.isBlank();
    }

    public boolean isPhotoAgeing() {
        return photoUploadedAt != null
                && photoUploadedAt.isBefore(OffsetDateTime.now().minusYears(2));
    }


}
