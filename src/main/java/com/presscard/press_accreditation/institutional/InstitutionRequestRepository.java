package com.presscard.press_accreditation.institutional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface InstitutionRequestRepository extends JpaRepository<InstitutionRequest, Long> {

    Optional<InstitutionRequest> findByVerificationTokenHash(String hash);

    List<InstitutionRequest> findByStatusOrderByCreatedAtDesc(InstitutionRequestStatus status);

    List<InstitutionRequest> findAllByOrderByCreatedAtDesc();

    long countByStatus(InstitutionRequestStatus status);

    /** Whether this address already has a request waiting. */
    @Query("""
           SELECT COUNT(r) > 0 FROM InstitutionRequest r
           WHERE lower(r.email) = lower(:email)
             AND r.status IN (com.presscard.press_accreditation.institutional.InstitutionRequestStatus.SUBMITTED,
                              com.presscard.press_accreditation.institutional.InstitutionRequestStatus.PENDING_REVIEW)
           """)
    boolean hasOpenRequest(@Param("email") String email);

    /** Requests whose address was never confirmed in time. */
    @Query("""
           SELECT r FROM InstitutionRequest r
           WHERE r.status = com.presscard.press_accreditation.institutional.InstitutionRequestStatus.SUBMITTED
             AND r.verificationExpiresAt < :now
           """)
    List<InstitutionRequest> findUnconfirmedBefore(@Param("now") OffsetDateTime now);
}
