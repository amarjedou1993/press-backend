package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.card.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InstitutionalCardRepository extends JpaRepository<InstitutionalCard, Long> {

    /** The verification lookup — by token, never by card number. */
    Optional<InstitutionalCard> findByVerificationToken(String verificationToken);

    Optional<InstitutionalCard> findByCardNumber(String cardNumber);

    /** One institution's roll, newest filing first. */
    List<InstitutionalCard> findByInstitutionIdOrderByFiledAtDesc(Long institutionId);

    /** Everything the Ministry has yet to grant, oldest first. */
    @Query("""
           SELECT c FROM InstitutionalCard c
           WHERE c.grantedAt IS NULL
           ORDER BY c.filedAt ASC
           """)
    List<InstitutionalCard> findAwaitingGrant();

    /**
     * ⚠️ Oldest first, deliberately — an institution that filed in January
     * should not wait behind one that filed last week.
     */
    @Query("""
           SELECT c FROM InstitutionalCard c
           WHERE c.institutionId = :institutionId
             AND c.grantedAt IS NULL
           ORDER BY c.filedAt ASC
           """)
    List<InstitutionalCard> findAwaitingGrantFor(@Param("institutionId") Long institutionId);

    /**
     * The ones a producer may make.
     *
     * ⚠️ THE FILTER IS HERE, NOT IN THE RESPONSE MAPPING — and it carries the
     * photograph clause that findProducible on honour cards had to be
     * corrected for. Without it a faceless card reaches the printer's queue
     * silently, and a QR that resolves to a verification page showing no one
     * verifies nothing.
     */
    @Query("""
           SELECT c FROM InstitutionalCard c
           WHERE c.grantedAt IS NOT NULL
             AND c.status = :status
             AND c.expiresAt >= CURRENT_DATE
             AND c.photoPath IS NOT NULL
           ORDER BY c.cardNumber ASC
           """)
    List<InstitutionalCard> findProducible(@Param("status") CardStatus status);

    /**
     * Whether this person already holds a live card here.
     *
     * ⚠️ Backed by a partial unique index, so a race loses at the database.
     * Read first so the message explains rather than surfacing a constraint
     * name.
     */
    @Query("""
           SELECT COUNT(c) > 0 FROM InstitutionalCard c
           WHERE c.institutionId = :institutionId
             AND c.identityNumber = :identityNumber
             AND c.grantedAt IS NOT NULL
             AND c.status <> com.presscard.press_accreditation.card.CardStatus.REVOKED
           """)
    boolean holderHasLiveCard(@Param("institutionId") Long institutionId,
                              @Param("identityNumber") String identityNumber);

    /** Which of these identities already hold one — one query for an import. */
    @Query("""
           SELECT c.identityNumber FROM InstitutionalCard c
           WHERE c.institutionId = :institutionId
             AND c.identityNumber IN :identityNumbers
             AND c.grantedAt IS NOT NULL
             AND c.status <> com.presscard.press_accreditation.card.CardStatus.REVOKED
           """)
    List<String> findLiveIdentityNumbers(@Param("institutionId") Long institutionId,
                                         @Param("identityNumbers") Collection<String> identityNumbers);

    /** C - 0001 / 26 — its own sequence, never the A or B series'. */
    @Query(value = "SELECT nextval('institutional_card_number_seq')", nativeQuery = true)
    Long nextCardNumber();
}
